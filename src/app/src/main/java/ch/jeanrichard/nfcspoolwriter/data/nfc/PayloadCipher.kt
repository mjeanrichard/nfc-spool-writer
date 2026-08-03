package ch.jeanrichard.nfcspoolwriter.data.nfc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts the 48-byte sector-1 payload (TAG_FORMAT_SPEC.md §6).
 *
 * Distinct from [KeyDerivation] in both key and purpose: this protects the payload *content*, uses a
 * single key identical for every tag, and is applied in addition to the per-tag sector-key gate.
 *
 * Sector 2's 48 bytes are never encrypted — they are written and read as plain ASCII.
 */
object PayloadCipher {

    /** Sector 1's three data blocks hold 3 × 16 bytes. */
    const val PAYLOAD_LENGTH = 48

    /** Fixed key "K2", used *only* for the payload. ASCII `H@CFkRnz@KAtBJp2`. */
    private val K2 = byteArrayOf(
        72, 64, 67, 70, 107, 82, 110, 122, 64, 75, 65, 116, 66, 74, 112, 50,
    )

    fun encrypt(plaintext: ByteArray): ByteArray = transform(Cipher.ENCRYPT_MODE, plaintext)

    fun decrypt(ciphertext: ByteArray): ByteArray = transform(Cipher.DECRYPT_MODE, ciphertext)

    /**
     * No padding is needed because the input is always exactly 3 AES blocks. ECB processes those
     * independently, so this is equivalent to three separate block operations — but the primitive is
     * called once on the whole 48 bytes rather than splitting blocks by hand (§6).
     */
    private fun transform(mode: Int, input: ByteArray): ByteArray {
        require(input.size == PAYLOAD_LENGTH) {
            "payload must be exactly $PAYLOAD_LENGTH bytes, was ${input.size}"
        }
        val cipher = Cipher.getInstance(AES_ECB_NO_PADDING).apply {
            init(mode, SecretKeySpec(K2, AES))
        }
        return cipher.doFinal(input)
    }
}
