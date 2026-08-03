package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for a real bug: payload bytes were converted with `US_ASCII`, which substitutes
 * U+FFFD for anything ≥ `0x80` and **destroys the value**. A genuine Creality tag (2026-08-04) held a
 * high byte in its reserve field, so this was silently corrupting real tags on read — and because the
 * write path verifies by comparing a re-read payload against what was intended, a lossy decode could
 * also make differing bytes compare equal.
 */
class TagBytesTest {

    @Test
    fun `high bytes survive a round-trip`() {
        val bytes = hexToBytes("00 7F 80 A5 FF 76 3F")

        val restored = bytes.toTagText().toTagBytes()

        assertEquals(bytes.toHex(), restored.toHex())
    }

    /** The exact failure mode: US-ASCII would turn a high byte into `0x3F` ('?'). */
    @Test
    fun `a high byte is not degraded to a question mark`() {
        val restored = hexToBytes("A5").toTagText().toTagBytes()

        assertEquals("A5", restored.toHex())
    }

    @Test
    fun `every possible byte value round-trips`() {
        val all = ByteArray(256) { it.toByte() }

        assertEquals(all.toHex(), all.toTagText().toTagBytes().toHex())
    }

    @Test
    fun `each byte maps to exactly one character`() {
        assertEquals(256, ByteArray(256) { it.toByte() }.toTagText().length)
    }

    @Test
    fun `ascii text is unaffected`() {
        val text = "AB1240276A2101001"

        assertEquals(text, text.toTagBytes().toTagText())
    }

    @Test
    fun `hex dump is space-separated uppercase`() {
        assertEquals("00 7F A5 FF", hexToBytes("007FA5FF").toHexDump())
    }

    @Test
    fun `escaping reveals nul and high bytes`() {
        val text = hexToBytes("41 00 A5 7E").toTagText()

        assertEquals("A\\x00\\xA5~", text.escapeNonPrintable())
    }

    @Test
    fun `escaping leaves printable ascii alone`() {
        assertEquals("Hello 123", "Hello 123".escapeNonPrintable())
    }
}
