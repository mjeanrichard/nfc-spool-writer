package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import java.io.IOException

/**
 * In-memory MIFARE Classic 1K stand-in for [MifareTagReaderWriter] tests.
 *
 * Models the parts of the real tag that the orchestration logic actually depends on: per-sector Key
 * A, authentication state, and block storage. Faults are injectable so the failure branches — tag
 * pulled away mid-write, a write that fails once then succeeds, a block that silently stores the
 * wrong bytes — are reachable without hardware.
 *
 * Its fidelity to the real adapter is an assumption, not a proof; confirming it takes manual
 * validation on real hardware.
 */
class FakeMifareSession(
    override val uid: ByteArray = hexToBytes("11223344"),
    /** Key A per sector. Absent means the MIFARE factory default. */
    sectorKeys: Map<Int, ByteArray> = emptyMap(),
    private val blocks: MutableMap<Int, ByteArray> = mutableMapOf(),
) : MifareSession {

    private val keys: MutableMap<Int, ByteArray> = sectorKeys.toMutableMap()
    private val authenticatedSectors = mutableSetOf<Int>()

    var connected = false
        private set
    var closed = false
        private set

    /** Every block write that reached storage, in order — lets tests assert what was written. */
    val writeLog = mutableListOf<Pair<Int, ByteArray>>()

    // --- Fault injection -------------------------------------------------------------------

    /** Throw on [connect]. */
    var failConnect = false

    /** Blocks that throw on read. */
    val unreadableBlocks = mutableSetOf<Int>()

    /**
     * Block -> number of consecutive write attempts to fail before succeeding. `Int.MAX_VALUE`
     * simulates a tag that is gone for good.
     */
    val writeFailures = mutableMapOf<Int, Int>()

    /** Counts every write attempt including failed ones, so retry behaviour can be asserted. */
    val writeAttempts = mutableMapOf<Int, Int>()

    /** Blocks that accept a write but store something else — simulates a silently bad write. */
    val corruptOnWrite = mutableMapOf<Int, ByteArray>()

    // --- MifareSession ---------------------------------------------------------------------

    /** Counts reconnects, so probe-retry behaviour can be asserted. */
    var reconnectCount = 0
        private set

    override fun connect() {
        if (failConnect) throw IOException("fake: tag out of range")
        connected = true
    }

    override fun reconnect() {
        reconnectCount++
        authenticatedSectors.clear()
        if (failConnect) throw IOException("fake: tag out of range")
        connected = true
    }

    /**
     * Number of authentication attempts to spuriously reject before behaving normally. Reproduces the
     * real-hardware flakiness where a genuine tag reported neither key on one tap and the derived key
     * on the next.
     */
    var spuriousAuthFailures = 0

    /**
     * 1-based positions of authentication calls to reject, counting every call in the session. The
     * counterpart to [spuriousAuthFailures], which rejects the *first* few: naming positions reaches
     * a re-authentication in the middle of an operation without disturbing the ones before it.
     */
    val failAuthenticationsAt = mutableSetOf<Int>()

    private var authentications = 0

    override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean {
        checkConnected()
        if (spuriousAuthFailures > 0) {
            spuriousAuthFailures--
            authenticatedSectors -= sector
            return false
        }
        if (++authentications in failAuthenticationsAt) {
            authenticatedSectors -= sector
            return false
        }
        val expected = keys[sector] ?: KeyDerivation.DEFAULT_KEY
        return if (key.contentEquals(expected)) {
            authenticatedSectors += sector
            true
        } else {
            // A real tag drops authentication for the sector on a failed attempt.
            authenticatedSectors -= sector
            false
        }
    }

    override fun readBlock(block: Int): ByteArray {
        checkConnected()
        requireAuthenticated(block)
        if (block in unreadableBlocks) throw IOException("fake: read of block $block failed")
        return blocks[block]?.copyOf() ?: ByteArray(MifareLayout.BLOCK_SIZE)
    }

    override fun writeBlock(block: Int, data: ByteArray) {
        checkConnected()
        requireAuthenticated(block)
        require(data.size == MifareLayout.BLOCK_SIZE) {
            "fake: block writes must be ${MifareLayout.BLOCK_SIZE} bytes, was ${data.size}"
        }

        writeAttempts[block] = (writeAttempts[block] ?: 0) + 1

        val remainingFailures = writeFailures[block] ?: 0
        if (remainingFailures > 0) {
            if (remainingFailures != Int.MAX_VALUE) writeFailures[block] = remainingFailures - 1
            throw IOException("fake: write to block $block failed")
        }

        val stored = corruptOnWrite[block] ?: data
        blocks[block] = stored.copyOf()
        writeLog += block to stored.copyOf()

        // Writing a sector trailer changes that sector's Key A, exactly as the real tag does.
        if (block.isTrailer()) {
            keys[block / MifareLayout.BLOCKS_PER_SECTOR] =
                stored.copyOf(KeyDerivation.KEY_LENGTH)
        }
    }

    override fun close() {
        closed = true
        connected = false
        authenticatedSectors.clear()
    }

    // --- Inspection helpers for assertions -------------------------------------------------

    fun blockOrNull(block: Int): ByteArray? = blocks[block]?.copyOf()

    fun keyForSector(sector: Int): ByteArray = keys[sector] ?: KeyDerivation.DEFAULT_KEY

    /**
     * A second session over the same tag storage and keys. `read`/`write` close the session they are
     * given, so verifying persisted state afterwards needs a fresh one — the same as re-tapping the
     * physical tag.
     */
    fun reopened(): FakeMifareSession =
        FakeMifareSession(uid = uid, sectorKeys = keys.toMap(), blocks = blocks)

    /**
     * Sets a block's contents directly, bypassing authentication and fault injection. For setting up
     * damage that is already present on the tag, rather than damage caused by the code under test.
     */
    fun forceBlock(block: Int, data: ByteArray) {
        blocks[block] = data.copyOf()
    }

    private fun checkConnected() {
        if (!connected) throw IllegalStateException("fake: session used before connect()")
    }

    private fun requireAuthenticated(block: Int) {
        val sector = block / MifareLayout.BLOCKS_PER_SECTOR
        if (sector !in authenticatedSectors) {
            throw IOException("fake: sector $sector not authenticated (block $block)")
        }
    }

    private fun Int.isTrailer(): Boolean =
        this % MifareLayout.BLOCKS_PER_SECTOR == MifareLayout.BLOCKS_PER_SECTOR - 1

    companion object {
        /** A factory-fresh tag: default keys everywhere, all data blocks zeroed. */
        fun blank(uid: ByteArray = hexToBytes("11223344")): FakeMifareSession =
            FakeMifareSession(uid = uid, blocks = defaultTrailers())

        /**
         * A tag already carrying [payload] under the derived key for [uid] — i.e. what a previous
         * successful write leaves behind.
         *
         * @param sectorTwoKey overrides the default key sector 2 is expected to be on, for the case
         *   where the plaintext half cannot be read at all.
         */
        fun written(
            payload: String,
            uid: ByteArray = hexToBytes("11223344"),
            sectorTwoKey: ByteArray? = null,
        ): FakeMifareSession {
            require(payload.length == 96) { "payload must be 96 characters" }
            val derivedKey = KeyDerivation.deriveSectorKey(uid)
            val primary = PayloadCipher.encrypt(payload.take(48).toByteArray(Charsets.ISO_8859_1))
            val secondary = payload.drop(48).toByteArray(Charsets.ISO_8859_1)

            val blocks = defaultTrailers()
            MifareLayout.primaryDataBlocks.forEachIndexed { i, block ->
                blocks[block] = primary.copyOfRange(i * 16, (i + 1) * 16)
            }
            MifareLayout.secondaryDataBlocks.forEachIndexed { i, block ->
                blocks[block] = secondary.copyOfRange(i * 16, (i + 1) * 16)
            }
            blocks[MifareLayout.PRIMARY_TRAILER_BLOCK] =
                MifareLayout.trailerInstalling(derivedKey, defaultTrailer())

            return FakeMifareSession(
                uid = uid,
                sectorKeys = buildMap {
                    put(MifareLayout.PRIMARY_SECTOR, derivedKey)
                    sectorTwoKey?.let { put(MifareLayout.SECONDARY_SECTOR, it) }
                },
                blocks = blocks,
            )
        }

        /**
         * The MIFARE transport-configuration trailer: default Key A, access bits `FF 07 80`, GPB
         * `69`. The access bits and GPB are the bytes this format must preserve untouched (spec §8),
         * so they are deliberately non-zero here — zeros would let a bug that drops them pass.
         */
        fun defaultTrailer(): ByteArray = hexToBytes("FFFFFFFFFFFF FF078069 FFFFFFFFFFFF")

        private fun defaultTrailers(): MutableMap<Int, ByteArray> = mutableMapOf(
            MifareLayout.PRIMARY_TRAILER_BLOCK to defaultTrailer(),
            11 to defaultTrailer(),
        )
    }
}
