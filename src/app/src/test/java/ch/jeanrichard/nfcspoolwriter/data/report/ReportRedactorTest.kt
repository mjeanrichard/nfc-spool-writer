package ch.jeanrichard.nfcspoolwriter.data.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportRedactorTest {

    private fun redact(text: String, vararg servers: String) =
        ReportRedactor.redact(text, servers.toList())

    // --- Configured server ---------------------------------------------------------------------

    @Test
    fun `the configured address is removed whole, ignoring case and a trailing slash`() {
        assertEquals(
            "GET <server>/api/v1/spool failed",
            redact("GET HTTP://Spoolman.LAN:7912/api/v1/spool failed", "http://spoolman.lan:7912/"),
        )
    }

    /** A resolver error names only the host, which no generic rule could tell from a word. */
    @Test
    fun `the configured host is removed on its own`() {
        assertEquals(
            "cannot reach <server> right now",
            redact("cannot reach spoolman.lan right now", "http://spoolman.lan:7912"),
        )
    }

    @Test
    fun `blank addresses are ignored rather than matching everywhere`() {
        assertEquals("nothing to hide", redact("nothing to hide", "", "  "))
    }

    @Test
    fun `an address without a host is still removed literally`() {
        assertEquals("tried <server> once", redact("tried spoolman once", "spoolman"))
    }

    @Test
    fun `an address that is not a valid URI is still removed literally`() {
        assertEquals("tried <server> once", redact("tried http://bad host once", "http://bad host"))
    }

    @Test
    fun `without configured addresses only the generic rules apply`() {
        assertEquals("plain text", ReportRedactor.redact("plain text"))
    }

    // --- Generic rules -------------------------------------------------------------------------

    @Test
    fun `urls are removed`() {
        assertEquals(
            "timeout [url=<url> connect_timeout=1000]",
            redact("timeout [url=https://example.org:7912/api/v1/health, connect_timeout=1000]"),
        )
    }

    @Test
    fun `quoted text is removed`() {
        assertEquals(
            "Unable to resolve host \"<redacted>\": No address",
            redact("Unable to resolve host \"spoolman.home\": No address"),
        )
    }

    @Test
    fun `json echoed by the parser is removed to the end of the line`() {
        assertEquals(
            "Unexpected token at offset 4\nJSON input: <redacted>\n\tat Foo.bar(Foo.kt:1)",
            redact(
                "Unexpected token at offset 4\nJSON input: {id: 7, filament: PLA}\n" +
                    "\tat Foo.bar(Foo.kt:1)"
            ),
        )
    }

    @Test
    fun `ipv4 addresses are removed, but not version numbers or stack frames`() {
        assertEquals(
            "Failed to connect to /<ip> (port 7912) from 1.2.3.4.5 at Foo.bar(Foo.kt:174)",
            redact("Failed to connect to /192.168.1.20 (port 7912) from 1.2.3.4.5 at Foo.bar(Foo.kt:174)"),
        )
    }

    @Test
    fun `ipv6 addresses are removed, compressed or not`() {
        assertEquals(
            "to /<ip> and [<ip>] and <ip>",
            redact("to /fe80::1 and [2001:db8:0:0:0:0:2:1] and fd00::abcd:7912"),
        )
    }

    @Test
    fun `colons in exception chains are left alone`() {
        val trace = "Caused by: java.io.IOException: Tag was lost.\n\tat Foo.bar(Foo.kt:12)"
        assertEquals(trace, redact(trace))
    }

    @Test
    fun `tag ids are removed in spaced, colon-separated and contiguous form`() {
        assertEquals(
            "Tag ( ID: <hex> ) is out of date; uid <hex>; raw <hex>",
            redact("Tag ( ID: 04 A2 3B 11 ) is out of date; uid 04:A2:3B:11; raw 04A23B11"),
        )
    }

    @Test
    fun `short hex runs and lowercase words are left alone`() {
        val text = "block 0A 0B 0C failed; be ad de af; DEADBEE"
        assertEquals(text, redact(text))
    }
}
