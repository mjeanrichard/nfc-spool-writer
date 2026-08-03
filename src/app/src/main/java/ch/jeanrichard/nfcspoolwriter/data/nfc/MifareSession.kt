package ch.jeanrichard.nfcspoolwriter.data.nfc

import java.io.IOException

/**
 * The handful of MIFARE Classic operations this app actually uses.
 *
 * This interface exists so that [MifareTagReaderWriter] — which holds all the branching logic worth
 * testing — never touches the Android framework. `android.nfc.tech.MifareClassic` is a final class
 * bound to a live `Tag`, so it can't be faked or exercised from a JVM unit test; isolating it here
 * means only the thin adapter is untested, rather than the retry/overwrite/verify logic (DESIGN.md
 * §1.2).
 */
interface MifareSession : AutoCloseable {

    /** The tag's UID. 4 bytes for a compatible tag; longer UIDs must be rejected (spec §3). */
    val uid: ByteArray

    /** @throws IOException if the tag cannot be connected to. */
    fun connect()

    /**
     * Drops and re-establishes the connection, clearing all authentication state.
     *
     * Needed because a failed authentication can leave a MIFARE Classic tag unwilling to accept
     * further attempts until the connection is reset — so trying a second key after the first is
     * rejected is not reliable without this.
     *
     * @throws IOException if the tag could not be reconnected (usually: it left the field).
     */
    fun reconnect()

    /**
     * @return true if [key] is the sector's Key A, false if it is not. A wrong key is an expected
     *   outcome (it's how blank-vs-written is detected), not an error.
     * @throws IOException if the tag was lost mid-operation.
     */
    fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean

    /** @throws IOException if the read fails or the tag was lost. */
    fun readBlock(block: Int): ByteArray

    /** @throws IOException if the write fails or the tag was lost. */
    fun writeBlock(block: Int, data: ByteArray)

    override fun close()
}

/** MIFARE Classic geometry and the layout this format uses (spec §1, §2). */
object MifareLayout {
    const val BLOCK_SIZE = 16
    const val BLOCKS_PER_SECTOR = 4

    /** Encrypted primary payload, gated by the per-tag derived key. */
    const val PRIMARY_SECTOR = 1
    val primaryDataBlocks = listOf(4, 5, 6)
    const val PRIMARY_TRAILER_BLOCK = 7

    /** Plaintext secondary payload, gated by the MIFARE default key. Trailer never modified. */
    const val SECONDARY_SECTOR = 2
    val secondaryDataBlocks = listOf(8, 9, 10)

    /**
     * Builds a sector trailer that installs [key] as both Key A and Key B while preserving the
     * access bits and general-purpose byte exactly as they were read from the tag.
     *
     * This format does not define or change access bits — it only ever swaps in new key values
     * (spec §8). Bytes 6–9 of [currentTrailer] are carried over verbatim; the key bytes in the
     * existing trailer are ignored, since a tag typically reads them back as zeros anyway.
     */
    fun trailerInstalling(key: ByteArray, currentTrailer: ByteArray): ByteArray {
        require(key.size == KeyDerivation.KEY_LENGTH) {
            "key must be ${KeyDerivation.KEY_LENGTH} bytes, was ${key.size}"
        }
        require(currentTrailer.size == BLOCK_SIZE) {
            "trailer must be $BLOCK_SIZE bytes, was ${currentTrailer.size}"
        }
        return ByteArray(BLOCK_SIZE).also { trailer ->
            key.copyInto(trailer, destinationOffset = 0)
            currentTrailer.copyInto(trailer, destinationOffset = 6, startIndex = 6, endIndex = 10)
            key.copyInto(trailer, destinationOffset = 10)
        }
    }
}
