package ch.jeanrichard.nfcspoolwriter.data.spoolman

import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * HTTP access to a Spoolman server.
 *
 * Spoolman has no built-in authentication, so there is no token handling here (DESIGN.md DEC-06).
 * Every method takes the base URL explicitly rather than reading settings, keeping the client
 * a pure function of its inputs — [SpoolmanRepository] is what knows where the URL comes from.
 */
class SpoolmanApiClient(private val httpClient: HttpClient) {

    /**
     * `GET /api/v1/health`, used by the settings screen's test-connection action.
     *
     * Deliberately the lightest endpoint: it confirms the address really points at a Spoolman (rather
     * than any host that happens to answer on that port) without pulling a spool list.
     */
    suspend fun testConnection(baseUrl: String): SpoolmanResult<Unit> =
        request(baseUrl, listOf("health")) { response ->
            // Shape-check the body; a proxy or unrelated service can return 200 with anything.
            val health = response.body<HealthDto>()
            if (health.status.isBlank()) {
                throw SerializationException("health response had no status")
            }
        }

    /**
     * `GET /api/v1/spool` — a page of spools, newest-registered first by default.
     *
     * Filters map to Spoolman's own partial, case-insensitive search parameters. Archived spools are
     * excluded unless asked for, since a tag for an archived spool is almost never wanted.
     */
    suspend fun listSpools(
        baseUrl: String,
        filamentName: String? = null,
        material: String? = null,
        vendorName: String? = null,
        location: String? = null,
        allowArchived: Boolean = false,
        sort: String? = DEFAULT_SORT,
        limit: Int? = null,
        offset: Int? = null,
    ): SpoolmanResult<SpoolPage> = request(baseUrl, listOf("spool"), {
        filamentName?.takeIf { it.isNotBlank() }?.let { parameters.append("filament.name", it) }
        material?.takeIf { it.isNotBlank() }?.let { parameters.append("filament.material", it) }
        vendorName?.takeIf { it.isNotBlank() }?.let { parameters.append("filament.vendor.name", it) }
        location?.takeIf { it.isNotBlank() }?.let { parameters.append("location", it) }
        if (allowArchived) parameters.append("allow_archived", "true")
        sort?.takeIf { it.isNotBlank() }?.let { parameters.append("sort", it) }
        limit?.let { parameters.append("limit", it.toString()) }
        offset?.let { parameters.append("offset", it.toString()) }
    }) { response ->
        SpoolPage(
            spools = response.body<List<SpoolDto>>().map { it.toDomain() },
            // Left null when the header is absent, unparseable or negative, rather than substituted
            // with the page size: a caller that cannot tell a reported total from a guessed one has to
            // ignore it entirely, which is how pagination loses its stopping condition. A negative
            // count is as much a non-answer as no header at all, and saying so here keeps every caller
            // from having to re-check the sign.
            totalCount = response.headers[TOTAL_COUNT_HEADER]?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    /** `GET /api/v1/spool/{id}` — re-read one spool, e.g. to refresh it just before writing a tag. */
    suspend fun getSpool(baseUrl: String, spoolId: Int): SpoolmanResult<Spool> = request(
        baseUrl = baseUrl,
        pathSegments = listOf("spool", spoolId.toString()),
        notFoundError = { SpoolmanError.SpoolNotFound(spoolId) },
    ) { response -> response.body<SpoolDto>().toDomain() }

    /**
     * Shared request pipeline: builds the URL, performs the call, and converts every failure mode into
     * a typed [SpoolmanError]. Centralised so no endpoint can accidentally leak an exception to the UI
     * or forget to distinguish "unreachable" from "answered with an error".
     */
    private suspend fun <T> request(
        baseUrl: String,
        pathSegments: List<String>,
        configureUrl: URLBuilder.() -> Unit = {},
        notFoundError: (() -> SpoolmanError)? = null,
        parse: suspend (HttpResponse) -> T,
    ): SpoolmanResult<T> {
        val url = buildUrl(baseUrl, pathSegments, configureUrl)
            ?: return SpoolmanResult.Failure(SpoolmanError.InvalidUrl(baseUrl))

        val response = try {
            httpClient.get(url)
        } catch (e: IOException) {
            return SpoolmanResult.Failure(SpoolmanError.Unreachable(baseUrl, e))
        } catch (e: TimeoutCancellationException) {
            return SpoolmanResult.Failure(SpoolmanError.Unreachable(baseUrl, e))
        } catch (e: HttpRequestTimeoutException) {
            return SpoolmanResult.Failure(SpoolmanError.Unreachable(baseUrl, e))
        }

        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.NotFound && notFoundError != null) {
                return SpoolmanResult.Failure(notFoundError())
            }
            val body = runCatching { response.bodyAsText().take(ERROR_BODY_LIMIT) }.getOrNull()
            return SpoolmanResult.Failure(SpoolmanError.HttpStatus(response.status.value, body))
        }

        return try {
            SpoolmanResult.Success(parse(response))
        } catch (e: SerializationException) {
            SpoolmanResult.Failure(SpoolmanError.MalformedResponse(e.message))
        } catch (e: JsonConvertException) {
            SpoolmanResult.Failure(SpoolmanError.MalformedResponse(e.message))
        }
    }

    /**
     * @return the absolute request URL, or null if [baseUrl] can't be parsed.
     *
     * Tolerates a missing scheme by assuming `http://`: Spoolman is typically a plain-HTTP service on a
     * LAN, and users naturally type `spoolman.local:7912`. Without this, that input parses as a
     * relative path and fails in a way that looks like the server is down.
     */
    private fun buildUrl(
        baseUrl: String,
        pathSegments: List<String>,
        configureUrl: URLBuilder.() -> Unit,
    ): Url? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme =
            if (trimmed.contains("://")) trimmed else "$DEFAULT_SCHEME://$trimmed"

        return try {
            URLBuilder().apply {
                takeFrom(withScheme)
                if (host.isBlank()) return null
                appendPathSegments(API_PREFIX)
                appendPathSegments(pathSegments)
                configureUrl()
            }.build()
        } catch (_: URLParserException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val API_PREFIX = "api/v1"
        const val TOTAL_COUNT_HEADER = "x-total-count"
        const val DEFAULT_SCHEME = "http"

        /** Most-recently-registered first: the spool just added is the one most likely to be tagged. */
        const val DEFAULT_SORT = "registered:desc"

        private const val ERROR_BODY_LIMIT = 500
    }
}

/**
 * @param totalCount total matching spools on the server, which may exceed [spools] when paging, or
 *   null when the server did not report one — Spoolman always sends `x-total-count`, but a reverse
 *   proxy can strip it. Null means *unknown*, never zero or "same as this page": a caller that needs a
 *   number must supply its own fallback, and a caller paging through the list must fall back to
 *   treating a short page as the end.
 */
data class SpoolPage(
    val spools: List<Spool>,
    val totalCount: Int?,
)

@Serializable
internal data class HealthDto(val status: String)
