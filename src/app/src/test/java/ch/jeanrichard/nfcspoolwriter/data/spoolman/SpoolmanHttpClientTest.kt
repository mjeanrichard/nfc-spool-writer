package ch.jeanrichard.nfcspoolwriter.data.spoolman

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the client's cross-cutting configuration, not any Spoolman endpoint (those are
 * [SpoolmanApiClientTest]'s job): that JSON decodes through ContentNegotiation, that fields the app
 * doesn't know about are tolerated, and that a non-2xx response is returned rather than thrown.
 */
class SpoolmanHttpClientTest {

    @Serializable
    private data class Probe(val id: Int, val name: String)

    @Test
    fun `decodes json response bodies`() = runTest {
        val client = createSpoolmanHttpClient(
            MockEngine {
                respond(
                    content = """{"id":42,"name":"Generic PLA"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        )

        val probe: Probe = client.get("http://spoolman.local/probe").body()

        assertEquals(Probe(id = 42, name = "Generic PLA"), probe)
    }

    @Test
    fun `ignores unknown fields so a newer Spoolman still deserializes`() = runTest {
        val client = createSpoolmanHttpClient(
            MockEngine {
                respond(
                    content = """{"id":42,"name":"Generic PLA","field_added_in_a_later_release":true}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        )

        val probe: Probe = client.get("http://spoolman.local/probe").body()

        assertEquals(Probe(id = 42, name = "Generic PLA"), probe)
    }

    @Test
    fun `non-2xx responses are returned instead of thrown`() = runTest {
        val client = createSpoolmanHttpClient(
            MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) }
        )

        val response = client.get("http://spoolman.local/probe")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
