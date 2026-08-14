package ch.jeanrichard.nfcspoolwriter.data.spoolman

import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.InMemoryPreferencesDataStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolmanRepositoryTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun spoolJson(id: Int) = """
        {"id":$id,"registered":"x","used_weight":0,"used_length":0,"archived":false,"extra":{},
         "filament":{"id":1,"registered":"x","density":1.24,"diameter":1.75,"extra":{},
                     "material":"PLA","weight":1000.0}}
    """.trimIndent()

    private fun repository(
        baseUrl: String?,
        totalCount: Int? = null,
        respondWith: (offset: Int) -> String,
    ): SpoolmanRepository {
        val settings = SettingsRepository(InMemoryPreferencesDataStore())
        val engine = MockEngine { request ->
            requests += request
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            respond(
                content = respondWith(offset),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    // null models a proxy stripping the header, so it is omitted entirely.
                    *listOfNotNull(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        totalCount?.let {
                            SpoolmanApiClient.TOTAL_COUNT_HEADER to listOf(it.toString())
                        },
                    ).toTypedArray()
                ),
            )
        }
        val repo = SpoolmanRepository(
            SpoolmanApiClient(createSpoolmanHttpClient(engine)),
            settings,
        )
        if (baseUrl != null) {
            kotlinx.coroutines.runBlocking { settings.setSpoolmanBaseUrl(baseUrl) }
        }
        return repo
    }

    /** First-run state: nothing configured yet, which must be a typed error rather than a crash. */
    @Test
    fun `an unconfigured server is reported as not configured`() = runTest {
        val repo = repository(baseUrl = null) { "[]" }

        val result = repo.listSpools()

        assertEquals(SpoolmanError.NotConfigured, (result as SpoolmanResult.Failure).error)
        assertTrue("no request should be made", requests.isEmpty())
    }

    @Test
    fun `getSpool is also gated on configuration`() = runTest {
        val repo = repository(baseUrl = null) { "[]" }

        assertEquals(
            SpoolmanError.NotConfigured,
            (repo.getSpool(1) as SpoolmanResult.Failure).error,
        )
    }

    /** Test-connection takes an unsaved address, so it must work before anything is configured. */
    @Test
    fun `testConnection works without a saved url`() = runTest {
        val repo = repository(baseUrl = null) { """{"status":"healthy"}""" }

        val result = repo.testConnection("http://spoolman.local:7912")

        assertTrue(result is SpoolmanResult.Success)
    }

    @Test
    fun `uses the configured base url`() = runTest {
        val repo = repository("http://configured.local:7912") { "[]" }

        repo.listSpools()

        assertEquals("configured.local", requests.single().url.host)
    }

    // --- Paging ----------------------------------------------------------------------------

    @Test
    fun `loadAllSpools returns a single page unchanged`() = runTest {
        val repo = repository("http://h", totalCount = 2) {
            "[${spoolJson(1)},${spoolJson(2)}]"
        }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertEquals(listOf(1, 2), page.spools.map { it.id })
        assertEquals(1, requests.size)
    }

    @Test
    fun `loadAllSpools follows pagination until the total is reached`() = runTest {
        val total = SpoolmanRepository.PAGE_LIMIT + 3
        val repo = repository("http://h", totalCount = total) { offset ->
            val remaining = (total - offset).coerceAtLeast(0)
            val count = minOf(SpoolmanRepository.PAGE_LIMIT, remaining)
            (1..count).joinToString(",", "[", "]") { spoolJson(offset + it) }
        }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertEquals(total, page.spools.size)
        assertEquals(total, page.totalCount)
        assertEquals(2, requests.size)
    }

    /** A server that keeps promising more rows than it delivers must not loop forever. */
    @Test
    fun `loadAllSpools stops at an empty page even if the total disagrees`() = runTest {
        val repo = repository("http://h", totalCount = 10_000) { offset ->
            if (offset == 0) {
                (1..SpoolmanRepository.PAGE_LIMIT).joinToString(",", "[", "]") { spoolJson(it) }
            } else {
                "[]"
            }
        }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertEquals(SpoolmanRepository.PAGE_LIMIT, page.spools.size)
        assertEquals(2, requests.size)
    }

    /** A short page means the list is exhausted; no follow-up request is owed. */
    @Test
    fun `loadAllSpools stops at a short page without another request`() = runTest {
        val repo = repository("http://h", totalCount = 10_000) { "[${spoolJson(1)}]" }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertEquals(1, page.spools.size)
        assertEquals(1, requests.size)
    }

    /** A proxy can strip x-total-count; a full page must still be followed up, not reported as all. */
    @Test
    fun `loadAllSpools keeps paging when the total count header is missing`() = runTest {
        val total = SpoolmanRepository.PAGE_LIMIT + 3
        val repo = repository("http://h", totalCount = null) { offset ->
            val remaining = (total - offset).coerceAtLeast(0)
            val count = minOf(SpoolmanRepository.PAGE_LIMIT, remaining)
            (1..count).joinToString(",", "[", "]") { spoolJson(offset + it) }
        }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertEquals(total, page.spools.size)
        // The real total is unknowable without the header; reporting the collected size keeps the
        // UI from claiming the list was cut short.
        assertEquals(total, page.totalCount)
        assertEquals(2, requests.size)
    }

    @Test
    fun `loadAllSpools caps the number of spools it will collect`() = runTest {
        val repo = repository("http://h", totalCount = 1_000_000) { offset ->
            (1..SpoolmanRepository.PAGE_LIMIT).joinToString(",", "[", "]") { spoolJson(offset + it) }
        }

        val page = (repo.loadAllSpools() as SpoolmanResult.Success).value

        assertTrue(page.spools.size >= SpoolmanRepository.MAX_SPOOLS)
        // Still reports the server's real total, so the UI can say the list was truncated.
        assertEquals(1_000_000, page.totalCount)
    }

    @Test
    fun `loadAllSpools propagates a failure from any page`() = runTest {
        val repo = SpoolmanRepository(
            SpoolmanApiClient(
                createSpoolmanHttpClient(
                    MockEngine { throw java.io.IOException("dropped") }
                )
            ),
            SettingsRepository(InMemoryPreferencesDataStore()).also {
                kotlinx.coroutines.runBlocking { it.setSpoolmanBaseUrl("http://h") }
            },
        )

        val result = repo.loadAllSpools()

        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.Unreachable)
    }
}
