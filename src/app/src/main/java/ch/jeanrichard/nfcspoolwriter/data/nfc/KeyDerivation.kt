package ch.jeanrichard.nfcspoolwriter.data.nfc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Derives a tag's sector-1 MIFARE key from its UID (TAG_FORMAT_SPEC.md §5).
 *
 * This gates *authentication* to sector 1 only; it has nothing to do with encrypting the content
 * stored there (that is [PayloadCipher], a separate key and a separate purpose — §4 warns these are
 * easy to conflate). Because the derivation is deterministic and the UID is readable without
 * authenticating, any device implementing this can compute the same key without storing it.
 *
 * The fixed key below is not a secret: it is inherent to the Creality format and embedded in every
 * implementation of it. This is not a security boundary (REQUIREMENTS.md NFR-11).
 */
object KeyDerivation {

    /** The 4-byte UID this format requires (§3). */
    const val UID_LENGTH = 4

    /** MIFARE Classic keys are 6 bytes. */
    const val KEY_LENGTH = 6

    /** A factory tag's sectors are all on this well-known MIFARE default (§1). */
    val DEFAULT_KEY: ByteArray get() = ByteArray(KEY_LENGTH) { 0xFF.toByte() }

    /** Fixed key "K1", used *only* for this derivation. ASCII `q3bu^t1nqfZ(pf$1`. */
    private val K1 = byteArrayOf(
        113, 51, 98, 117, 94, 116, 49, 110, 113, 102, 90, 40, 112, 102, 36, 49,
    )

    /**
     * @param uid the tag's 4-byte UID.
     * @return the 6-byte sector key used as Key A for sector 1.
     * @throws IllegalArgumentException if [uid] is not 4 bytes — 7-byte "double size" UIDs are not
     *   compatible with this format and must be rejected before any write is attempted (§3).
     */
    fun deriveSectorKey(uid: ByteArray): ByteArray {
        require(uid.size == UID_LENGTH) {
            "this format requires a 4-byte UID, got ${uid.size} bytes"
        }

        // Tile the UID to fill one 16-byte AES block, then keep the first 6 bytes of the output.
        val block = ByteArray(AES_BLOCK_SIZE) { uid[it % UID_LENGTH] }

        val cipher = Cipher.getInstance(AES_ECB_NO_PADDING).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(K1, AES))
        }
        return cipher.doFinal(block).copyOf(KEY_LENGTH)
    }
}

internal const val AES = "AES"
internal const val AES_ECB_NO_PADDING = "AES/ECB/NoPadding"
internal const val AES_BLOCK_SIZE = 16
