package ch.jeanrichard.nfcspoolwriter.data.spoolman

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * JSON configuration shared by the Spoolman API client and the bundled material catalog.
 *
 * [ignoreUnknownKeys] matters here: Spoolman adds fields across releases, and the app must keep
 * working against a newer server than it was built for rather than failing to deserialize.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Builds the HTTP client used for Spoolman calls. No base URL is configured here — it lives in
 * settings and is applied per-request by the API client, since the user can change it at runtime
 * without the client being rebuilt.
 *
 * The [engine] parameter exists so unit tests can pass Ktor's `MockEngine` in place of OkHttp.
 */
fun createSpoolmanHttpClient(engine: HttpClientEngine = OkHttp.create()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false // non-2xx is mapped to typed errors by the API client, not thrown here
        install(ContentNegotiation) {
            json(AppJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

/**
 * Short enough that an unreachable self-hosted server surfaces an error while the user is still
 * looking at the screen, rather than appearing to hang.
 */
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 15_000L
