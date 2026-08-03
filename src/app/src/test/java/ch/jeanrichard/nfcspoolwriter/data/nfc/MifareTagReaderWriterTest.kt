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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(TagWriteResult.Success, result)
    }

    @Test
    fun `written blank tag reads back the same fields`() = runTest {
        val session = FakeMifareSession.blank()
        readerWriter.write(session, fields, allowOverwrite = false)

        // A fresh session over the same storage, since write() closes the one it was given.
        val reread = readerWriter.read(session.reopened())

        assertEquals(TagReadResult.Written(TagCodec.decode(payload)), reread)
    }

    @Test
    fun `installs the derived key on sector 1 when writing a blank tag`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

        val expected = KeyDerivation.deriveSectorKey(session.uid)
        assertEquals(expected.toHex(), session.keyForSector(MifareLayout.PRIMARY_SECTOR).toHex())
    }

    /** The format must never redefine access bits — it only swaps key values (spec §8). */
    @Test
    fun `preserves access bits and GPB when installing the sector key`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

        val trailer = session.blockOrNull(MifareLayout.PRIMARY_TRAILER_BLOCK)!!
        assertEquals("FF078069", trailer.copyOfRange(6, 10).toHex())
    }

    @Test
    fun `installs the derived key as both Key A and Key B`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

        val trailer = session.blockOrNull(MifareLayout.PRIMARY_TRAILER_BLOCK)!!
        val key = KeyDerivation.deriveSectorKey(session.uid).toHex()
        assertEquals(key, trailer.copyOfRange(0, 6).toHex())
        assertEquals(key, trailer.copyOfRange(10, 16).toHex())
    }

    @Test
    fun `never modifies the sector 2 trailer`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(FakeMifareSession.defaultTrailer().toHex(), session.blockOrNull(11)!!.toHex())
    }

    @Test
    fun `stores sector 1 encrypted and sector 2 as plaintext`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, updated, allowOverwrite = true)

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

        readerWriter.write(session, fields, allowOverwrite = true)

        assertTrue(session.writeLog.none { it.first == MifareLayout.PRIMARY_TRAILER_BLOCK })
    }

    // --- Write: overwrite protection -------------------------------------------------------

    @Test
    fun `reports overwrite required for an already-written tag`() = runTest {
        val session = FakeMifareSession.written(payload)

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(
            TagWriteResult.OverwriteRequired(TagReadResult.Written(TagCodec.decode(payload))),
            result,
        )
    }

    @Test
    fun `writes nothing when overwrite is refused`() = runTest {
        val session = FakeMifareSession.written(payload)

        readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        val existing = (result as TagWriteResult.OverwriteRequired).existing
        assertTrue("expected Corrupt, was $existing", existing is TagReadResult.Corrupt)
    }

    @Test
    fun `recovers a corrupt tag when overwrite is permitted`() = runTest {
        val session = FakeMifareSession.written(payload).apply { writeCorruptPrimaryBlock() }

        val result = readerWriter.write(session, fields, allowOverwrite = true)

        assertEquals(TagWriteResult.Success, result)
    }

    // --- Write: failures -------------------------------------------------------------------

    @Test
    fun `rejects a 7-byte UID without writing`() = runTest {
        val session = FakeMifareSession.blank(uid = hexToBytes("11223344556677"))

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(
            TagWriteResult.Failed(TagFailure.IncompatibleUidLength, partiallyWritten = false),
            result,
        )
        assertTrue(session.writeLog.isEmpty())
    }

    @Test
    fun `incompatible UID is not retryable`() = runTest {
        val session = FakeMifareSession.blank(uid = hexToBytes("11223344556677"))

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(TagWriteResult.Success, result)
        assertEquals(3, session.writeAttempts[MifareLayout.primaryDataBlocks.first()])
    }

    @Test
    fun `gives up after exhausting write attempts`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(false, (result as TagWriteResult.Failed).partiallyWritten)
    }

    /** The user must be warned the tag is inconsistent and needs a full rewrite (REQUIREMENTS §5). */
    @Test
    fun `a failure after some blocks were written reports partially written`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.last()] = Int.MAX_VALUE

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        val failed = result as TagWriteResult.Failed
        assertEquals(true, failed.partiallyWritten)
        assertEquals(true, failed.failure.retryable)
    }

    @Test
    fun `tag lost on connect is a retryable failure`() = runTest {
        val session = FakeMifareSession.blank().apply { failConnect = true }

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

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

        val result = readerWriter.write(session, fields, allowOverwrite = false)

        assertTrue((result as TagWriteResult.Failed).failure is TagFailure.VerifyMismatch)
    }

    @Test
    fun `closes the session on success`() = runTest {
        val session = FakeMifareSession.blank()

        readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(true, session.closed)
    }

    @Test
    fun `closes the session on failure`() = runTest {
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = Int.MAX_VALUE

        readerWriter.write(session, fields, allowOverwrite = false)

        assertEquals(true, session.closed)
    }

    @Test
    fun `single-attempt configuration does not retry`() = runTest {
        val single = MifareTagReaderWriter(writeAttempts = 1, retryDelayMillis = 0)
        val session = FakeMifareSession.blank()
        session.writeFailures[MifareLayout.primaryDataBlocks.first()] = 1

        val result = single.write(session, fields, allowOverwrite = false)

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
