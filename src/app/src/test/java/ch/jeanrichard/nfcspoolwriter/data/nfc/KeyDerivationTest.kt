package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyDerivationTest {

    /** The reproducible vector from TAG_FORMAT_SPEC.md §5/§13. */
    @Test
    fun `derives the spec's worked-example key`() {
        val key = KeyDerivation.deriveSectorKey(hexToBytes("11223344"))

        assertEquals("16785090548E", key.toHex())
    }

    @Test
    fun `derived key is 6 bytes`() {
        val key = KeyDerivation.deriveSectorKey(hexToBytes("11223344"))

        assertEquals(KeyDerivation.KEY_LENGTH, key.size)
    }

    @Test
    fun `derivation is deterministic`() {
        val first = KeyDerivation.deriveSectorKey(hexToBytes("DEADBEEF"))
        val second = KeyDerivation.deriveSectorKey(hexToBytes("DEADBEEF"))

        assertEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `different UIDs derive different keys`() {
        val a = KeyDerivation.deriveSectorKey(hexToBytes("11223344"))
        val b = KeyDerivation.deriveSectorKey(hexToBytes("11223345"))

        assertEquals(false, a.toHex() == b.toHex())
    }

    @Test
    fun `all-zero UID is accepted`() {
        // Not a realistic tag, but the algorithm has no special case for it and must not throw.
        val key = KeyDerivation.deriveSectorKey(hexToBytes("00000000"))

        assertEquals(KeyDerivation.KEY_LENGTH, key.size)
    }

    /** 7-byte "double size" UIDs must be rejected, not silently truncated (§3). */
    @Test
    fun `rejects a 7-byte UID`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveSectorKey(hexToBytes("11223344556677"))
        }

        assertEquals(true, error.message!!.contains("4-byte UID"))
    }

    @Test
    fun `rejects an empty UID`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyDerivation.deriveSectorKey(ByteArray(0))
        }
    }

    @Test
    fun `default key is six FF bytes`() {
        assertEquals("FFFFFFFFFFFF", KeyDerivation.DEFAULT_KEY.toHex())
    }

    /** The getter must hand out a fresh array; a shared one could be mutated by a caller. */
    @Test
    fun `default key is not a shared mutable instance`() {
        KeyDerivation.DEFAULT_KEY[0] = 0

        assertEquals("FFFFFFFFFFFF", KeyDerivation.DEFAULT_KEY.toHex())
    }
}
