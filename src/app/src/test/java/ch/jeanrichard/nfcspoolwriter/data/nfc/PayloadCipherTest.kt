package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayloadCipherTest {

    private val plaintext = "AB1240276A21010010FF0000033000004200004200000000"

    /** As laid out in TAG_FORMAT_SPEC.md §6/§10, one line per destination block (4, 5, 6). */
    private val expectedCiphertext = """
        57 F2 5B 78 07 6D 4C 17 97 B1 BE 35 CA 26 95 40
        11 A0 4C 3E 0D 0B DF 51 73 4B 01 1C 49 C7 A9 AB
        8B B6 FB 2E F6 77 26 58 4C 4E F2 27 E4 EB 89 7C
    """.trimIndent()

    @Test
    fun `plaintext vector is exactly one payload half`() {
        // Guards the test itself: a typo shortening the string would make the vector meaningless.
        assertEquals(PayloadCipher.PAYLOAD_LENGTH, plaintext.length)
    }

    @Test
    fun `encrypts to the spec's worked-example ciphertext`() {
        val actual = PayloadCipher.encrypt(plaintext.toByteArray(Charsets.ISO_8859_1))

        assertEquals(hexToBytes(expectedCiphertext).toHex(), actual.toHex())
    }

    @Test
    fun `decrypts the spec's worked-example ciphertext back to the original string`() {
        val actual = PayloadCipher.decrypt(hexToBytes(expectedCiphertext))

        assertEquals(plaintext, String(actual, Charsets.ISO_8859_1))
    }

    @Test
    fun `round-trips arbitrary payloads`() {
        val input = ByteArray(PayloadCipher.PAYLOAD_LENGTH) { it.toByte() }

        val output = PayloadCipher.decrypt(PayloadCipher.encrypt(input))

        assertEquals(input.toHex(), output.toHex())
    }

    /**
     * ECB has no chaining, so identical 16-byte blocks encrypt identically. Asserting it pins the
     * mode: were this accidentally changed to CBC, this test would fail rather than the change
     * silently producing tags no CFS reader can decrypt.
     */
    @Test
    fun `identical blocks encrypt identically confirming ECB mode`() {
        val repeated = ByteArray(PayloadCipher.PAYLOAD_LENGTH) { 0x41 }

        val out = PayloadCipher.encrypt(repeated).toHex()

        val first = out.substring(0, 32)
        assertEquals(first, out.substring(32, 64))
        assertEquals(first, out.substring(64, 96))
    }

    @Test
    fun `rejects a payload shorter than 48 bytes`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PayloadCipher.encrypt(ByteArray(47))
        }

        assertEquals(true, error.message!!.contains("48 bytes"))
    }

    @Test
    fun `rejects a payload longer than 48 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PayloadCipher.encrypt(ByteArray(64))
        }
    }

    @Test
    fun `rejects decrypting a wrong-length ciphertext`() {
        assertThrows(IllegalArgumentException::class.java) {
            PayloadCipher.decrypt(ByteArray(32))
        }
    }
}
