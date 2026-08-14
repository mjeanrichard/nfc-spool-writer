package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.TagCodec
import ch.jeanrichard.nfcspoolwriter.testsupport.hexToBytes
import ch.jeanrichard.nfcspoolwriter.testsupport.toHex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MifareTagReaderWriterTest {

    private val readerWriter = MifareTagReaderWriter(retryDelayMillis = 0)

    private val fields = MappedFields(
        filamentCatalogId = "01001",
        colorRgb = "FF0000",
        weight = WeightBucket.G1000,
        spoolmanSpoolId = 42,
    )
    private val payload = TagCodec.encode(fields)

    // --- Write: happy paths ----------------------------------------------------------------

    @Test
    fun `writes a blank tag and verifies it`() = runTest {
        val session = FakeMifareSession.blank()

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(TagWriteResult.Success, result)
    }

    @Test
    fun `written blank tag reads back the same fields`() = runTest {
        val session = FakeMifareSession.blank()
        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        // A fresh session over the same storage, since write() closes the one it was given.
        val reread = readerWriter.read(session.reopened())

        assertEquals(TagReadResult.Written(TagCodec.decode(payload)), reread)
    }

    @Test
    fun `installs the derived key on sector 1 when writing a blank tag`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val expected = KeyDerivation.deriveSectorKey(session.uid)
        assertEquals(expected.toHex(), session.keyForSector(MifareLayout.PRIMARY_SECTOR).toHex())
    }

    /** The format must never redefine access bits — it only swaps key values (spec §8). */
    @Test
    fun `preserves access bits and GPB when installing the sector key`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val trailer = session.blockOrNull(MifareLayout.PRIMARY_TRAILER_BLOCK)!!
        assertEquals("FF078069", trailer.copyOfRange(6, 10).toHex())
    }

    @Test
    fun `installs the derived key as both Key A and Key B`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val trailer = session.blockOrNull(MifareLayout.PRIMARY_TRAILER_BLOCK)!!
        val key = KeyDerivation.deriveSectorKey(session.uid).toHex()
        assertEquals(key, trailer.copyOfRange(0, 6).toHex())
        assertEquals(key, trailer.copyOfRange(10, 16).toHex())
    }

    @Test
    fun `never modifies the sector 2 trailer`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(FakeMifareSession.defaultTrailer().toHex(), session.blockOrNull(11)!!.toHex())
    }

    @Test
    fun `stores sector 1 encrypted and sector 2 as plaintext`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val primary = MifareLayout.primaryDataBlocks
            .map { session.blockOrNull(it)!! }
            .reduce(ByteArray::plus)
        assertNotEquals(payload.take(48), String(primary, Charsets.ISO_8859_1))
        assertEquals(payload.take(48), String(PayloadCipher.decrypt(primary), Charsets.ISO_8859_1))

        val secondary = MifareLayout.secondaryDataBlocks
            .map { session.blockOrNull(it)!! }
            .reduce(ByteArray::plus)
        assertEquals(payload.drop(48), String(secondary, Charsets.ISO_8859_1))
    }

    @Test
    fun `overwrites an already-written tag when permitted`() = runTest {
        val updated = fields.copy(spoolmanSpoolId = 99, colorRgb = "00FF00")
        val session = FakeMifareSession.written(payload)

        val result = readerWriter.write(session, updated, overwrite = OverwriteMode.Replace)

        assertEquals(TagWriteResult.Success, result)
        assertEquals(
            TagReadResult.Written(TagCodec.decode(TagCodec.encode(updated))),
            readerWriter.read(session.reopened()),
        )
    }

    /** An already-keyed tag needs no trailer rewrite; doing it anyway is a needless risk (spec §8). */
    @Test
    fun `does not rewrite the trailer on an already-secured tag`() = runTest {
        val session = FakeMifareSession.written(payload)

        readerWriter.write(session, fields, overwrite = OverwriteMode.Replace)

        assertTrue(session.writeLog.none { it.first == MifareLayout.PRIMARY_TRAILER_BLOCK })
    }

    // --- Write: overwrite protection -------------------------------------------------------

    @Test
    fun `reports overwrite required for an already-written tag`() = runTest {
        val session = FakeMifareSession.written(payload)

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(
            TagWriteResult.OverwriteRequired(TagReadResult.Written(TagCodec.decode(payload))),
            result,
        )
    }

    @Test
    fun `writes nothing when overwrite is refused`() = runTest {
        val session = FakeMifareSession.written(payload)

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertTrue(session.writeLog.isEmpty())
    }

    /**
     * A tag whose earlier write was interrupted has our key but unparseable content. It must still
     * take the already-secured branch, not be mistaken for blank — the key has already changed.
     */
    @Test
    fun `reports overwrite required with corrupt content for a half-written tag`() = runTest {
        val session = FakeMifareSession.written(payload).apply {
            writeCorruptPrimaryBlock()
        }

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val existing = (result as TagWriteResult.OverwriteRequired).existing
        assertTrue("expected Corrupt, was $existing", existing is TagReadResult.Corrupt)
    }

    @Test
    fun `recovers a corrupt tag when overwrite is permitted`() = runTest {
        val session = FakeMifareSession.written(payload).apply { writeCorruptPrimaryBlock() }

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Replace)

        assertEquals(TagWriteResult.Success, result)
    }

    // --- Write: spool ID only ----------------------------------------------------------------

    /**
     * The point of the mode: a tag carrying data this app did not author keeps every byte of it, and
     * only the field a printer resolves the spool from moves. The fields asserted here are the ones
     * the new spool would otherwise have replaced.
     */
    @Test
    fun `spool id only replaces the reserve id and keeps every other field`() = runTest {
        val existing = TagCodec.encode(
            fields.copy(
                filamentCatalogId = "02002",
                colorRgb = "0000FF",
                weight = WeightBucket.G500,
                batchNumber = "XY9",
                dateCode = "25027",
                spoolmanSpoolId = 7,
            )
        )
        val session = FakeMifareSession.written(existing)

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        assertEquals(TagWriteResult.Success, result)
        val reread = (readerWriter.read(session.reopened()) as TagReadResult.Written).payload
        assertEquals("000042", reread.reserve.take(6))
        assertEquals("02002", reread.fields.filamentCatalogId)
        assertEquals("0000FF", reread.fields.colorRgb)
        assertEquals(WeightBucket.G500, reread.fields.weight)
        assertEquals("XY9", reread.fields.batchNumber)
        assertEquals("25027", reread.fields.dateCode)
    }

    /**
     * A tag written this way names two spools — the new one in the reserve, the previous one in the
     * serial — and the app must report the one the printer acts on, or a read of the tag it just
     * wrote would contradict the success message.
     */
    @Test
    fun `an id-only tag reads back as the new spool, not the serial it kept`() = runTest {
        val existing = TagCodec.encode(fields.copy(spoolmanSpoolId = 7))
        val session = FakeMifareSession.written(existing)

        readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        val reread = (readerWriter.read(session.reopened()) as TagReadResult.Written).payload
        assertEquals(42, reread.fields.spoolmanSpoolId)
        assertEquals("000007", reread.serialNumber)
    }

    /**
     * The reserve's leading 6 characters are what a printer resolves the Spoolman spool from
     * (TAG_FORMAT_SPEC.md §9), so they must follow the new spool — a tag that kept them would go on
     * pointing the printer at the previous one. The trailing 8 are preserved: that is where a genuine
     * tag's unidentified bytes live (DESIGN.md DEC-08).
     */
    @Test
    fun `spool id only rewrites the reserve id and preserves its unidentified bytes`() = runTest {
        // What a genuine tag carries after the ID: one non-zero byte, then NULs.
        val unidentified = "v" + "\u0000".repeat(7)
        val existing = TagCodec.encode(fields.copy(spoolmanSpoolId = 7))
            .replaceRange(40, 48, unidentified)
        val session = FakeMifareSession.written(existing)

        readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        val reread = readerWriter.read(session.reopened()) as TagReadResult.Written
        assertEquals("000042" + unidentified, reread.payload.reserve)
    }

    /**
     * Everything outside the reserve's ID half is byte-identical, serial number and padding included.
     * Spool `7` → `42` differs only in its last two digits, so only the tail of `[34,40)` changes.
     */
    @Test
    fun `spool id only changes exactly the reserve id`() = runTest {
        val existing = TagCodec.encode(fields.copy(spoolmanSpoolId = 7))
        val session = FakeMifareSession.written(existing)

        readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        val written = session.payloadText()
        assertEquals(existing.length, written.length)
        val changed = existing.indices.filter { existing[it] != written[it] }
        assertEquals(listOf(38, 39), changed)
    }

    /** Nothing to preserve on a blank tag, so the mode degrades to a full write rather than failing. */
    @Test
    fun `spool id only writes a blank tag in full`() = runTest {
        val session = FakeMifareSession.blank()

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        assertEquals(TagWriteResult.Success, result)
        assertEquals(
            TagReadResult.Written(TagCodec.decode(payload)),
            readerWriter.read(session.reopened()),
        )
    }

    /**
     * Splicing an ID into bytes that do not decode would leave the tag as broken as it already is, so
     * the mode refuses rather than half-repairing it.
     */
    @Test
    fun `spool id only refuses a tag whose content does not decode`() = runTest {
        val session = FakeMifareSession.written(payload).apply { writeCorruptPrimaryBlock() }

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        val failed = result as TagWriteResult.Failed
        assertTrue(
            "expected ExistingContentUnreadable, was ${failed.failure}",
            failed.failure is TagFailure.ExistingContentUnreadable,
        )
        assertEquals(false, failed.partiallyWritten)
        assertEquals(false, failed.failure.retryable)
        assertTrue(session.writeLog.isEmpty())
    }

    /**
     * Sector 2 is read on the default key, and a MIFARE Classic session holds one sector at a time —
     * so sector 1 has to be re-authenticated before writing. A genuine tag has been observed
     * rejecting a correct key once and accepting it on the next attempt (`REQ-15`), and the key here
     * is already known to work, so one rejection must be retried rather than believed.
     */
    @Test
    fun `spool id only retries a spurious rejection when reclaiming sector 1`() = runTest {
        val session = FakeMifareSession.written(payload)
        // Two authentications succeed (probe, sector 2 during the read); the third is the reclaim.
        session.failAuthenticationsAt += 3

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        assertEquals(TagWriteResult.Success, result)
        assertEquals(1, session.reconnectCount)
    }

    /**
     * Once the retries are spent the write stops — but as a *retryable* failure and with nothing
     * written, because the key authenticated moments earlier in the probe and so the tag's key
     * scheme is not what is in doubt.
     */
    @Test
    fun `spool id only fails retryably when sector 1 cannot be reclaimed at all`() = runTest {
        val session = FakeMifareSession.written(payload)
        session.failAuthenticationsAt += setOf(3, 4)

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        val failed = result as TagWriteResult.Failed
        assertTrue("expected TagLost, was ${failed.failure}", failed.failure is TagFailure.TagLost)
        assertEquals(true, failed.failure.retryable)
        assertEquals(false, failed.partiallyWritten)
        assertTrue(session.writeLog.isEmpty())
    }

    /** Sector 2 on an unexpected key truncates the payload, which is not content that can be kept. */
    @Test
    fun `spool id only refuses when sector 2 cannot be read`() = runTest {
        val session = FakeMifareSession.written(payload, sectorTwoKey = hexToBytes("A0A1A2A3A4A5"))

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.SpoolIdOnly)

        assertTrue(
            (result as TagWriteResult.Failed).failure is TagFailure.ExistingContentUnreadable,
        )
    }

    // --- Write: failures -------------------------------------------------------------------

    @Test
    fun `rejects a 7-byte UID without writing`() = runTest {
        val session = FakeMifareSession.blank(uid = hexToBytes("11223344556677"))

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(
            TagWriteResult.Failed(TagFailure.IncompatibleUidLength, partiallyWritten = false),
            result,
        )
        assertTrue(session.writeLog.isEmpty())
    }

    @Test
    fun `incompatible UID is not retryable`() = runTest {
        val session = FakeMifareSession.blank(uid = hexToBytes("11223344556677"))

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(false, (result as TagWriteResult.Failed).failure.retryable)
    }

    /**
     * A genuine Creality tag reported neither key on one tap and authenticated on the next, so a
     * single failed probe must not be trusted — `UnknownKeyScheme` is terminal and would wrongly tell
     * the user their good tag uses an unrelated key scheme.
     */
    @Test
    fun `retries the probe after a spurious auth failure and succeeds`() = runTest {
        val session = FakeMifareSession.written(payload)
        // Fails both key attempts on the first probe, then behaves.
        session.spuriousAuthFailures = 2

        val result = readerWriter.read(session)

        assertEquals(TagReadResult.Written(TagCodec.decode(payload)), result)
        assertEquals(1, session.reconnectCount)
    }

    @Test
    fun `reconnects between probe attempts`() = runTest {
        val session = FakeMifareSession.blank()
        session.spuriousAuthFailures = 2

        readerWriter.read(session)

        // A failed authentication can leave the tag refusing further attempts until reconnected.
        assertEquals(1, session.reconnectCount)
    }

    @Test
    fun `write also retries a spurious probe failure`() = runTest {
        val session = FakeMifareSession.blank()
        session.spuriousAuthFailures = 2

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(TagWriteResult.Success, result)
    }

    @Test
    fun `single-probe configuration does not retry`() = runTest {
        val single = MifareTagReaderWriter(probeAttempts = 1, retryDelayMillis = 0)
        val session = FakeMifareSession.written(payload)
        session.spuriousAuthFailures = 2

        val result = single.read(session)

        assertEquals(TagReadResult.Failed(TagFailure.UnknownKeyScheme), result)
        assertEquals(0, session.reconnectCount)
    }

    @Test
    fun `surfaces an unrelated key scheme as a terminal failure`() = runTest {
        val session = FakeMifareSession(
            sectorKeys = mapOf(MifareLayout.PRIMARY_SECTOR to hexToBytes("A0A1A2A3A4A5")),
        )

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(
            TagWriteResult.Failed(TagFailure.UnknownKeyScheme, partiallyWritten = false),
            result,
        )
        assertEquals(false, (result as TagWriteResult.Failed).failure.retryable)
    }

    @Test
    fun `retries a failing block write and succeeds`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = 2

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(TagWriteResult.Success, result)
        assertEquals(3, session.writeAttempts[MifareLayout.primaryDataBlocks.first()])
    }

    @Test
    fun `gives up after exhausting write attempts`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val failed = result as TagWriteResult.Failed
        assertTrue("expected TagLost, was ${failed.failure}", failed.failure is TagFailure.TagLost)
        assertEquals(
            MifareTagReaderWriter.DEFAULT_WRITE_ATTEMPTS,
            session.writeAttempts[MifareLayout.primaryDataBlocks.first()],
        )
    }

    @Test
    fun `a failure before any block is written is not reported as partially written`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(false, (result as TagWriteResult.Failed).partiallyWritten)
    }

    /** The user must be warned the tag is inconsistent and needs a full rewrite (REQUIREMENTS §5). */
    @Test
    fun `a failure after some blocks were written reports partially written`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.last()] = Int.MAX_VALUE

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val failed = result as TagWriteResult.Failed
        assertEquals(true, failed.partiallyWritten)
        assertEquals(true, failed.failure.retryable)
    }

    @Test
    fun `tag lost on connect is a retryable failure`() = runTest {
        val session = FakeMifareSession.blank().apply { failConnect = true }

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val failed = result as TagWriteResult.Failed
        assertTrue(failed.failure is TagFailure.TagLost)
        assertEquals(true, failed.failure.retryable)
        assertEquals(false, failed.partiallyWritten)
    }

    /** A write that reports success but stores the wrong bytes must not be accepted. */
    @Test
    fun `verify mismatch after a silently bad write is a failure`() = runTest {
        val session = FakeMifareSession.blank()
        session.corruptOnWrite[MifareLayout.primaryDataBlocks.first()] =
            ByteArray(MifareLayout.BLOCK_SIZE) { 0x5A }

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        val failed = result as TagWriteResult.Failed
        assertTrue(
            "expected VerifyMismatch, was ${failed.failure}",
            failed.failure is TagFailure.VerifyMismatch,
        )
        assertEquals(true, failed.partiallyWritten)
    }

    @Test
    fun `verify mismatch in the plaintext sector is also caught`() = runTest {
        val session = FakeMifareSession.blank()
        session.corruptOnWrite[MifareLayout.secondaryDataBlocks.first()] =
            "wrong bytes here".toByteArray(Charsets.ISO_8859_1)

        val result = readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertTrue((result as TagWriteResult.Failed).failure is TagFailure.VerifyMismatch)
    }

    @Test
    fun `closes the session on success`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(true, session.closed)
    }

    @Test
    fun `closes the session on failure`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE

        readerWriter.write(session, fields, overwrite = OverwriteMode.Ask)

        assertEquals(true, session.closed)
    }

    @Test
    fun `single-attempt configuration does not retry`() = runTest {
        val single = MifareTagReaderWriter(writeAttempts = 1, retryDelayMillis = 0)
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = 1

        val result = single.write(session, fields, overwrite = OverwriteMode.Ask)

        assertTrue(result is TagWriteResult.Failed)
        assertEquals(1, session.writeAttempts[MifareLayout.primaryDataBlocks.first()])
    }

    /**
     * A genuine Creality tag holds a byte ≥ `0x80` in its reserve field. The read path must carry it
     * through untouched — a `US_ASCII` conversion silently replaced such bytes, corrupting real tags.
     */
    @Test
    fun `a payload containing a high byte survives the read path`() = runTest {
        // U+00A5 is byte 0xA5 in Latin-1 — the case US-ASCII would have destroyed.
        val highByte = "\u00A5"
        val session = FakeMifareSession.written(payload.replaceRange(40, 41, highByte))

        val result = readerWriter.read(session)

        assertEquals(highByte[0], (result as TagReadResult.Written).payload.reserve[6])
    }

    // --- Read ------------------------------------------------------------------------------

    @Test
    fun `reads a blank tag as blank`() = runTest {
        assertEquals(TagReadResult.Blank, readerWriter.read(FakeMifareSession.blank()))
    }

    @Test
    fun `reads a written tag`() = runTest {
        val result = readerWriter.read(FakeMifareSession.written(payload))

        assertEquals(TagReadResult.Written(TagCodec.decode(payload)), result)
    }

    @Test
    fun `reads an undecodable payload as corrupt rather than failing`() = runTest {
        val session = FakeMifareSession.written(payload).apply { writeCorruptPrimaryBlock() }

        val result = readerWriter.read(session)

        assertTrue("expected Corrupt, was $result", result is TagReadResult.Corrupt)
    }

    @Test
    fun `rejects reading a 7-byte UID tag`() = runTest {
        val session = FakeMifareSession.blank(uid = hexToBytes("11223344556677"))

        assertEquals(
            TagReadResult.Failed(TagFailure.IncompatibleUidLength),
            readerWriter.read(session),
        )
    }

    @Test
    fun `reports an unrelated key scheme when reading`() = runTest {
        val session = FakeMifareSession(
            sectorKeys = mapOf(MifareLayout.PRIMARY_SECTOR to hexToBytes("A0A1A2A3A4A5")),
        )

        assertEquals(
            TagReadResult.Failed(TagFailure.UnknownKeyScheme),
            readerWriter.read(session),
        )
    }

    @Test
    fun `reports tag lost when a read fails mid-payload`() = runTest {
        val session = FakeMifareSession.written(payload)
        session.unreadableBlocks += MifareLayout.primaryDataBlocks.last()

        val result = readerWriter.read(session)

        assertTrue((result as TagReadResult.Failed).failure is TagFailure.TagLost)
    }

    @Test
    fun `closes the session after reading`() = runTest {
        val session = FakeMifareSession.written(payload)

        readerWriter.read(session)

        assertEquals(true, session.closed)
    }
}

/**
 * Corrupts one encrypted block so the payload no longer decodes — what an interrupted write leaves
 * behind. Bypasses the session API deliberately: this is damage that already exists on the tag.
 */
private fun FakeMifareSession.writeCorruptPrimaryBlock() {
    forceBlock(MifareLayout.primaryDataBlocks.first(), ByteArray(MifareLayout.BLOCK_SIZE))
}

/**
 * The payload the tag holds right now, reassembled the way the format defines it (spec §11). Reads
 * the stored blocks directly rather than through a session, so it can be used to compare a tag
 * against what it held before a write.
 */
private fun FakeMifareSession.payloadText(): String {
    val blocksOf = { blocks: List<Int> -> blocks.map { blockOrNull(it)!! }.reduce(ByteArray::plus) }
    val primary = PayloadCipher.decrypt(blocksOf(MifareLayout.primaryDataBlocks))
    return String(primary, Charsets.ISO_8859_1) +
        String(blocksOf(MifareLayout.secondaryDataBlocks), Charsets.ISO_8859_1)
}
