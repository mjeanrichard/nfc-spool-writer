package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagCodec
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagDecodeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads and writes Creality CFS tags over a [MifareSession], implementing the algorithms in
 * TAG_FORMAT_SPEC.md §11 (read) and §12 (write).
 *
 * All the branching worth testing lives here rather than in the Android adapter: tag-state probing,
 * trailer-key installation, retry, and verify-after-write. Against a fake session every one of those
 * branches is reachable from a JVM unit test (DESIGN.md §1.2).
 *
 * @param writeAttempts how many times a single block write is tried before giving up. Retries cover
 *   transient RF glitches while the tag is still present; if the tag has actually left the field the
 *   session is dead and no number of retries helps, which is why the caller gets a retryable failure
 *   and asks for another tap instead.
 */
class MifareTagReaderWriter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writeAttempts: Int = DEFAULT_WRITE_ATTEMPTS,
    private val probeAttempts: Int = DEFAULT_PROBE_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MS,
) {
    init {
        require(writeAttempts >= 1) { "writeAttempts must be at least 1, was $writeAttempts" }
        require(probeAttempts >= 1) { "probeAttempts must be at least 1, was $probeAttempts" }
    }

    suspend fun read(session: MifareSession): TagReadResult = withContext(ioDispatcher) {
        try {
            session.use {
                it.connect()
                val derivedKey = deriveKeyOrNull(it.uid)
                    ?: return@withContext TagReadResult.Failed(TagFailure.IncompatibleUidLength)

                when (probeState(it, derivedKey)) {
                    TagState.Blank -> TagReadResult.Blank
                    TagState.Unknown -> TagReadResult.Failed(TagFailure.UnknownKeyScheme)
                    TagState.Secured -> readSecuredPayload(it)
                }
            }
        } catch (e: IOException) {
            TagReadResult.Failed(TagFailure.TagLost(e))
        }
    }

    /**
     * @param overwrite what may happen to a tag that already holds data. With [OverwriteMode.Ask]
     *   such a tag is reported as [TagWriteResult.OverwriteRequired] and nothing is written
     *   (REQUIREMENTS.md §5).
     */
    suspend fun write(
        session: MifareSession,
        fields: MappedFields,
        overwrite: OverwriteMode,
    ): TagWriteResult = withContext(ioDispatcher) {
        var partiallyWritten = false
        try {
            session.use {
                it.connect()
                val derivedKey = deriveKeyOrNull(it.uid)
                    ?: return@withContext TagWriteResult.Failed(
                        TagFailure.IncompatibleUidLength,
                        partiallyWritten = false,
                    )

                val state = probeState(it, derivedKey)
                if (state == TagState.Unknown) {
                    return@withContext TagWriteResult.Failed(
                        TagFailure.UnknownKeyScheme,
                        partiallyWritten = false,
                    )
                }

                // Only a secured tag has content worth asking about or worth keeping; a blank one is
                // written in full whatever mode was asked for.
                val payload = if (state != TagState.Secured) {
                    TagCodec.encode(fields)
                } else when (overwrite) {
                    // Still authenticated to sector 1 from the probe, so the existing content can be
                    // shown to the user in the confirmation prompt.
                    OverwriteMode.Ask ->
                        return@withContext TagWriteResult.OverwriteRequired(readSecuredPayload(it))

                    OverwriteMode.Replace -> TagCodec.encode(fields)

                    OverwriteMode.SpoolIdOnly -> {
                        val spliced = try {
                            TagCodec.withSpoolId(readRawPayload(it), fields.spoolmanSpoolId)
                        } catch (e: TagDecodeException) {
                            return@withContext TagWriteResult.Failed(
                                TagFailure.ExistingContentUnreadable(
                                    e.message ?: "existing payload could not be decoded"
                                ),
                                partiallyWritten = false,
                            )
                        }
                        // Reading the payload ends on sector 2, and a MIFARE Classic session holds
                        // one sector at a time — sector 1 has to be claimed again before writing it.
                        if (!reauthenticate(it, MifareLayout.PRIMARY_SECTOR, derivedKey)) {
                            return@withContext TagWriteResult.Failed(
                                // Not UnknownKeyScheme: this same key authenticated moments ago in
                                // the probe, so the tag's key scheme is not in doubt and the verdict
                                // must stay retryable.
                                TagFailure.TagLost(
                                    IOException(
                                        "sector 1 could not be re-authenticated after reading the " +
                                            "tag's existing content"
                                    )
                                ),
                                partiallyWritten = false,
                            )
                        }
                        spliced
                    }
                }

                val primaryCiphertext =
                    PayloadCipher.encrypt(payload.take(TagCodec.HALF_LENGTH).toTagBytes())
                val secondaryPlaintext = payload.drop(TagCodec.HALF_LENGTH).toTagBytes()

                // Sector 1 data blocks. The probe left us authenticated with whichever key worked.
                primaryCiphertext.toBlocks().forEachIndexed { index, block ->
                    writeBlockWithRetry(it, MifareLayout.primaryDataBlocks[index], block)
                    partiallyWritten = true
                }

                // A blank tag is still on the default key, so install the derived key as both Key A
                // and Key B, preserving the existing access bits and GPB exactly (spec §8).
                if (state == TagState.Blank) {
                    val currentTrailer = it.readBlock(MifareLayout.PRIMARY_TRAILER_BLOCK)
                    writeBlockWithRetry(
                        session = it,
                        block = MifareLayout.PRIMARY_TRAILER_BLOCK,
                        data = MifareLayout.trailerInstalling(derivedKey, currentTrailer),
                    )
                    // The sector's key just changed underneath us; re-authenticate before any
                    // further access to sector 1 (the verify read below).
                    if (!it.authenticateSectorWithKeyA(MifareLayout.PRIMARY_SECTOR, derivedKey)) {
                        return@withContext TagWriteResult.Failed(
                            TagFailure.VerifyMismatch(
                                "sector-1 trailer was written but the derived key no longer " +
                                    "authenticates, so the installed key is not what was intended"
                            ),
                            partiallyWritten = true,
                        )
                    }
                }

                // Sector 2 is always on the default key and its trailer is never modified (spec §8).
                if (!it.authenticateSectorWithKeyA(
                        MifareLayout.SECONDARY_SECTOR,
                        KeyDerivation.DEFAULT_KEY,
                    )
                ) {
                    return@withContext TagWriteResult.Failed(
                        TagFailure.UnknownKeyScheme,
                        partiallyWritten = true,
                    )
                }
                secondaryPlaintext.toBlocks().forEachIndexed { index, block ->
                    writeBlockWithRetry(it, MifareLayout.secondaryDataBlocks[index], block)
                }

                verify(it, derivedKey, expected = payload)
            }
        } catch (e: IOException) {
            TagWriteResult.Failed(TagFailure.TagLost(e), partiallyWritten)
        }
    }

    /**
     * Re-reads the tag and compares it against what was intended, so a write is never reported as
     * successful on the strength of the write calls alone (spec §12 step 9).
     *
     * Compares the whole 96-character payload rather than field-by-field: it is strictly stronger,
     * and it also catches a corrupted reserve field, which no [MappedFields] comparison would see.
     */
    private fun verify(
        session: MifareSession,
        derivedKey: ByteArray,
        expected: String,
    ): TagWriteResult {
        if (!session.authenticateSectorWithKeyA(MifareLayout.PRIMARY_SECTOR, derivedKey)) {
            return TagWriteResult.Failed(
                TagFailure.VerifyMismatch("sector 1 no longer authenticates with the derived key"),
                partiallyWritten = true,
            )
        }
        val actual = readRawPayload(session)
        return if (actual == expected) {
            TagWriteResult.Success
        } else {
            TagWriteResult.Failed(
                TagFailure.VerifyMismatch(
                    "tag content does not match what was written\n" +
                        "expected: $expected\n" +
                        "actual:   $actual"
                ),
                partiallyWritten = true,
            )
        }
    }

    /** Assumes sector 1 is already authenticated with the derived key. */
    private fun readSecuredPayload(session: MifareSession): TagReadResult {
        val raw = readRawPayload(session)
        return try {
            TagReadResult.Written(TagCodec.decode(raw))
        } catch (e: TagDecodeException) {
            TagReadResult.Corrupt(e.message ?: "payload could not be decoded")
        }
    }

    /**
     * Reads both halves and reassembles the 96-character payload: sector 1 decrypted, sector 2 as
     * plaintext (spec §11).
     */
    private fun readRawPayload(session: MifareSession): String {
        val primary = MifareLayout.primaryDataBlocks
            .map(session::readBlock)
            .reduce(ByteArray::plus)
        val part1 = PayloadCipher.decrypt(primary).toTagText()

        if (!session.authenticateSectorWithKeyA(
                MifareLayout.SECONDARY_SECTOR,
                KeyDerivation.DEFAULT_KEY,
            )
        ) {
            // Sector 2 is expected to be on the default key; if it isn't, there is no part 2 to
            // read. Returning a short string makes the payload fail to decode, which is the honest
            // outcome — the tag is not one this format can read.
            return part1
        }
        val part2 = MifareLayout.secondaryDataBlocks
            .map(session::readBlock)
            .reduce(ByteArray::plus)
            .toTagText()

        return part1 + part2
    }

    /**
     * Determines whether the tag is already secured by this format, still blank, or using unrelated
     * keys — by trying the derived key first, then the MIFARE default (spec §8).
     *
     * Leaves the session authenticated to sector 1 with whichever key succeeded.
     *
     * Retries the whole probe, reconnecting in between, before concluding [TagState.Unknown]. A
     * genuine Creality tag was observed reporting neither key on one tap and authenticating with the
     * derived key on the next, so a single failed probe is not trustworthy evidence about the tag —
     * and `Unknown` is a terminal, non-retryable verdict that would tell the user their perfectly
     * good tag uses an unrelated key scheme.
     */
    private fun probeState(session: MifareSession, derivedKey: ByteArray): TagState {
        repeat(probeAttempts) { attempt ->
            when (val state = probeOnce(session, derivedKey)) {
                TagState.Secured, TagState.Blank -> return state
                TagState.Unknown -> if (attempt < probeAttempts - 1) session.reconnect()
            }
        }
        return TagState.Unknown
    }

    /**
     * Claims [sector] again partway through an operation, retrying with a reconnect in between for
     * the same reason [probeState] does: a genuine tag has been observed rejecting a correct key on
     * one attempt and accepting it on the next (REQUIREMENTS.md `REQ-15`). A single rejection is not
     * evidence about the tag, and here the key is already known to work — the probe used it.
     */
    private fun reauthenticate(session: MifareSession, sector: Int, key: ByteArray): Boolean {
        repeat(probeAttempts) { attempt ->
            if (session.authenticateSectorWithKeyA(sector, key)) return true
            if (attempt < probeAttempts - 1) session.reconnect()
        }
        return false
    }

    private fun probeOnce(session: MifareSession, derivedKey: ByteArray): TagState = when {
        session.authenticateSectorWithKeyA(MifareLayout.PRIMARY_SECTOR, derivedKey) ->
            TagState.Secured

        session.authenticateSectorWithKeyA(
            MifareLayout.PRIMARY_SECTOR,
            KeyDerivation.DEFAULT_KEY,
        ) -> TagState.Blank

        else -> TagState.Unknown
    }

    private suspend fun writeBlockWithRetry(session: MifareSession, block: Int, data: ByteArray) {
        var lastError: IOException? = null
        repeat(writeAttempts) { attempt ->
            try {
                session.writeBlock(block, data)
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < writeAttempts - 1) delay(retryDelayMillis)
            }
        }
        throw lastError ?: IOException("write to block $block failed")
    }

    /** @return the derived sector key, or null if the UID length makes the tag incompatible. */
    private fun deriveKeyOrNull(uid: ByteArray): ByteArray? =
        if (uid.size == KeyDerivation.UID_LENGTH) KeyDerivation.deriveSectorKey(uid) else null

    private enum class TagState { Blank, Secured, Unknown }

    companion object {
        const val DEFAULT_WRITE_ATTEMPTS = 3

        /** Two is enough: the observed failure was a single spurious rejection, not a pattern. */
        const val DEFAULT_PROBE_ATTEMPTS = 2
        const val DEFAULT_RETRY_DELAY_MS = 50L
    }
}

private fun ByteArray.toBlocks(): List<ByteArray> =
    toList().chunked(MifareLayout.BLOCK_SIZE) { it.toByteArray() }
