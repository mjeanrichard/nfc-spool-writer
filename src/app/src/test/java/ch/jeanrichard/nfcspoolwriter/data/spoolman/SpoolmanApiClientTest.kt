package ch.jeanrichard.nfcspoolwriter.data.spoolman

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SpoolmanApiClientTest {

    private val baseUrl = "http://spoolman.local:7912"

    private val spoolJson = """
        {
          "id": 5,
          "registered": "2026-01-01T00:00:00Z",
          "used_weight": 100.0,
          "used_length": 30.0,
          "archived": false,
          "extra": {},
          "remaining_weight": 900.0,
          "location": "Shelf A",
          "lot_nr": "LOT-7",
          "filament": {
            "id": 7,
            "registered": "2026-01-01T00:00:00Z",
            "name": "PolyTerra PLA Blue",
            "material": "PLA",
            "density": 1.24,
            "diameter": 1.75,
            "weight": 1000.0,
            "color_hex": "0000FF",
            "extra": {},
            "vendor": {
              "id": 3,
              "registered": "2026-01-01T00:00:00Z",
              "name": "Polymaker",
              "extra": {}
            }
          }
        }
    """.trimIndent()

    /** Captures the requests the client makes so query-param construction can be asserted. */
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: io.ktor.http.Headers = headersOf(
            HttpHeaders.ContentType,
            ContentType.Application.Json.toString(),
        ),
        body: () -> String,
    ) = SpoolmanApiClient(
        createSpoolmanHttpClient(
            MockEngine { request ->
                requests += request
                respond(content = body(), status = status, headers = headers)
            }
        )
    )

    private fun failingClient(error: Throwable) =
        SpoolmanApiClient(createSpoolmanHttpClient(MockEngine { throw error }))

    // --- URL construction ------------------------------------------------------------------

    @Test
    fun `builds the health url under the api prefix`() = runTest {
        client { """{"status":"healthy"}""" }.testConnection(baseUrl)

        assertEquals("/api/v1/health", requests.single().url.encodedPath)
    }

    @Test
    fun `builds the spool list url`() = runTest {
        client { "[]" }.listSpools(baseUrl)

        assertEquals("/api/v1/spool", requests.single().url.encodedPath)
    }

    @Test
    fun `builds the single spool url`() = runTest {
        client { spoolJson }.getSpool(baseUrl, 5)

        assertEquals("/api/v1/spool/5", requests.single().url.encodedPath)
    }

    @Test
    fun `tolerates a trailing slash on the base url`() = runTest {
        client { "[]" }.listSpools("$baseUrl/")

        assertEquals("/api/v1/spool", requests.single().url.encodedPath)
    }

    @Test
    fun `preserves a base url path prefix for reverse-proxied instances`() = runTest {
        client { "[]" }.listSpools("http://host/spoolman")

        assertEquals("/spoolman/api/v1/spool", requests.single().url.encodedPath)
    }

    /** Users naturally type a bare host:port; assuming http beats failing as if the server were down. */
    @Test
    fun `assumes http when no scheme is given`() = runTest {
        client { "[]" }.listSpools("spoolman.local:7912")

        val url = requests.single().url
        assertEquals("http", url.protocol.name)
        assertEquals("spoolman.local", url.host)
        assertEquals(7912, url.port)
    }

    @Test
    fun `keeps an explicit https scheme`() = runTest {
        client { "[]" }.listSpools("https://spoolman.example.com")

        assertEquals("https", requests.single().url.protocol.name)
    }

    @Test
    fun `an empty base url is an invalid url error`() = runTest {
        val result = client { "[]" }.listSpools("   ")

        assertTrue(result is SpoolmanResult.Failure)
        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.InvalidUrl)
        assertTrue("no request should be made", requests.isEmpty())
    }

    // --- Query parameters ------------------------------------------------------------------

    @Test
    fun `sends spoolman's filter parameter names`() = runTest {
        client { "[]" }.listSpools(
            baseUrl,
            filamentName = "PolyTerra",
            material = "PLA",
            vendorName = "Polymaker",
            location = "Shelf A",
        )

        val params = requests.single().url.parameters
        assertEquals("PolyTerra", params["filament.name"])
        assertEquals("PLA", params["filament.material"])
        assertEquals("Polymaker", params["filament.vendor.name"])
        assertEquals("Shelf A", params["location"])
    }

    @Test
    fun `omits blank and null filters`() = runTest {
        client { "[]" }.listSpools(baseUrl, filamentName = "  ", material = null)

        val params = requests.single().url.parameters
        assertNull(params["filament.name"])
        assertNull(params["filament.material"])
    }

    /** A tag for an archived spool is almost never wanted, so archived are excluded by default. */
    @Test
    fun `excludes archived spools unless asked`() = runTest {
        client { "[]" }.listSpools(baseUrl)

        assertNull(requests.single().url.parameters["allow_archived"])
    }

    @Test
    fun `includes archived spools when asked`() = runTest {
        client { "[]" }.listSpools(baseUrl, allowArchived = true)

        assertEquals("true", requests.single().url.parameters["allow_archived"])
    }

    @Test
    fun `sends pagination parameters`() = runTest {
        client { "[]" }.listSpools(baseUrl, limit = 50, offset = 100)

        val params = requests.single().url.parameters
        assertEquals("50", params["limit"])
        assertEquals("100", params["offset"])
    }

    @Test
    fun `sends the default sort`() = runTest {
        client { "[]" }.listSpools(baseUrl)

        assertEquals(SpoolmanApiClient.DEFAULT_SORT, requests.single().url.parameters["sort"])
    }

    // --- Deserialization -------------------------------------------------------------------

    @Test
    fun `maps a spool onto the domain model`() = runTest {
        val result = client { "[$spoolJson]" }.listSpools(baseUrl)

        val spool = (result as SpoolmanResult.Success).value.spools.single()
        assertEquals(5, spool.id)
        assertEquals("Shelf A", spool.location)
        assertEquals("LOT-7", spool.lotNumber)
        assertEquals(900.0, spool.remainingWeightGrams)
        assertEquals("PLA", spool.filament.material)
        assertEquals("PolyTerra PLA Blue", spool.filament.name)
        assertEquals("0000FF", spool.filament.colorHex)
        assertEquals("Polymaker", spool.filament.vendor?.name)
    }

    /** Spoolman's `weight` is the full-spool weight — the value the tag's weight bucket encodes. */
    @Test
    fun `filament weight maps to the full spool weight`() = runTest {
        val result = client { "[$spoolJson]" }.listSpools(baseUrl)

        val spool = (result as SpoolmanResult.Success).value.spools.single()
        assertEquals(1000.0, spool.filament.fullSpoolWeightGrams)
        assertEquals(900.0, spool.remainingWeightGrams)
    }

    @Test
    fun `reads the total count header`() = runTest {
        val result = client(
            headers = headersOf(
                HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                SpoolmanApiClient.TOTAL_COUNT_HEADER to listOf("137"),
            )
        ) { "[$spoolJson]" }.listSpools(baseUrl)

        assertEquals(137, (result as SpoolmanResult.Success).value.totalCount)
    }

    /** A proxy may strip the header; falling back to the page size only loses the "n of m" display. */
    @Test
    fun `falls back to page size when the total count header is missing`() = runTest {
        val result = client { "[$spoolJson]" }.listSpools(baseUrl)

        assertEquals(1, (result as SpoolmanResult.Success).value.totalCount)
    }

    @Test
    fun `an empty result list is a success not an error`() = runTest {
        val result = client { "[]" }.listSpools(baseUrl)

        assertEquals(emptyList<Any>(), (result as SpoolmanResult.Success).value.spools)
    }

    @Test
    fun `optional filament fields may be absent`() = runTest {
        val sparse = """
            [{"id":1,"registered":"x","used_weight":0,"used_length":0,"archived":false,"extra":{},
              "filament":{"id":2,"registered":"x","density":1.24,"diameter":1.75,"extra":{}}}]
        """.trimIndent()

        val result = client { sparse }.listSpools(baseUrl)

        val spool = (result as SpoolmanResult.Success).value.spools.single()
        assertNull(spool.filament.material)
        assertNull(spool.filament.colorHex)
        assertNull(spool.filament.vendor)
        assertNull(spool.filament.fullSpoolWeightGrams)
    }

    /** Blank strings are normalized to null so the mapper doesn't treat "" as a real value. */
    @Test
    fun `blank strings become null`() = runTest {
        val blanks = """
            [{"id":1,"registered":"x","used_weight":0,"used_length":0,"archived":false,"extra":{},
              "location":"  ","lot_nr":"",
              "filament":{"id":2,"registered":"x","density":1.24,"diameter":1.75,"extra":{},
                          "material":"","color_hex":"  ","name":""}}]
        """.trimIndent()

        val result = client { blanks }.listSpools(baseUrl)

        val spool = (result as SpoolmanResult.Success).value.spools.single()
        assertNull(spool.location)
        assertNull(spool.lotNumber)
        assertNull(spool.filament.material)
        assertNull(spool.filament.colorHex)
        assertNull(spool.filament.name)
    }

    @Test
    fun `unknown fields are ignored so a newer Spoolman still works`() = runTest {
        val withExtras = """
            [{"id":1,"registered":"x","used_weight":0,"used_length":0,"archived":false,"extra":{},
              "field_added_later":true,
              "filament":{"id":2,"registered":"x","density":1.24,"diameter":1.75,"extra":{},
                          "another_new_field":[1,2,3]}}]
        """.trimIndent()

        val result = client { withExtras }.listSpools(baseUrl)

        assertEquals(1, (result as SpoolmanResult.Success).value.spools.single().id)
    }

    // --- Failure modes ---------------------------------------------------------------------

    @Test
    fun `malformed json is a typed error not a crash`() = runTest {
        val result = client { "{ this is not json" }.listSpools(baseUrl)

        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.MalformedResponse)
    }

    /** Something else answering on the port: valid JSON, wrong shape. */
    @Test
    fun `json of the wrong shape is a malformed response`() = runTest {
        val result = client { """{"unexpected":"object instead of array"}""" }.listSpools(baseUrl)

        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.MalformedResponse)
    }

    @Test
    fun `a spool missing its required filament is a malformed response`() = runTest {
        val result = client {
            """[{"id":1,"registered":"x","used_weight":0,"used_length":0,"archived":false,"extra":{}}]"""
        }.listSpools(baseUrl)

        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.MalformedResponse)
    }

    @Test
    fun `a 200 health response with the wrong body is rejected`() = runTest {
        val result = client { """{"not_status":"x"}""" }.testConnection(baseUrl)

        assertTrue((result as SpoolmanResult.Failure).error is SpoolmanError.MalformedResponse)
    }

    @Test
    fun `a healthy response succeeds`() = runTest {
        val result = client { """{"status":"healthy"}""" }.testConnection(baseUrl)

        assertTrue(result is SpoolmanResult.Success)
    }

    @Test
    fun `a 404 on a single spool is reported as spool not found`() = runTest {
        val client = SpoolmanApiClient(
            createSpoolmanHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        )

        val result = client.getSpool(baseUrl, 42)

        assertEquals(
            SpoolmanError.SpoolNotFound(42),
            (result as SpoolmanResult.Failure).error,
        )
    }

    @Test
    fun `a 500 is reported as an http status error`() = runTest {
        val client = SpoolmanApiClient(
            createSpoolmanHttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
        )

        val result = client.listSpools(baseUrl)

        val error = (result as SpoolmanResult.Failure).error
        assertEquals(500, (error as SpoolmanError.HttpStatus).code)
        assertTrue(error.userMessage.contains("500"))
    }

    /** Spoolman has no auth of its own, so a 401 means a proxy — the message must say so. */
    @Test
    fun `a 401 explains that a reverse proxy is the likely cause`() = runTest {
        val client = SpoolmanApiClient(
            createSpoolmanHttpClient(MockEngine { respondError(HttpStatusCode.Unauthorized) })
        )

        val error = (client.listSpools(baseUrl) as SpoolmanResult.Failure).error

        assertTrue(error.userMessage.contains("proxy"))
    }

    @Test
    fun `a 404 on the list endpoint is a plain http error not spool-not-found`() = runTest {
        val client = SpoolmanApiClient(
            createSpoolmanHttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        )

        val error = (client.listSpools(baseUrl) as SpoolmanResult.Failure).error

        assertTrue("expected HttpStatus, was $error", error is SpoolmanError.HttpStatus)
    }

    @Test
    fun `an unreachable host is a typed unreachable error`() = runTest {
        val result = failingClient(IOException("connect refused")).listSpools(baseUrl)

        val error = (result as SpoolmanResult.Failure).error
        assertTrue("expected Unreachable, was $error", error is SpoolmanError.Unreachable)
        assertTrue(error.userMessage.contains(baseUrl))
    }

    @Test
    fun `every error carries a non-empty user message`() {
        listOf(
            SpoolmanError.NotConfigured,
            SpoolmanError.InvalidUrl("nope"),
            SpoolmanError.Unreachable(baseUrl, null),
            SpoolmanError.SpoolNotFound(1),
            SpoolmanError.HttpStatus(503, null),
            SpoolmanError.MalformedResponse(null),
        ).forEach { error ->
            assertTrue("$error has no message", error.userMessage.isNotBlank())
            // No raw exception text or type names leaking into the UI.
            assertTrue("$error looks like a stack trace", !error.userMessage.contains("Exception"))
        }
    }
}
