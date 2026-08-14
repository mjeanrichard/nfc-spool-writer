package ch.jeanrichard.nfcspoolwriter.domain.tagcodec

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests built from a **real Creality CFS tag**, read on 2026-08-04 from a genuine spool
 * (UID `B3 E9 8C 94`) with the development harness.
 *
 * These exist because the reverse-engineered spec was wrong in three ways that only real hardware
 * revealed, and each was enough on its own to make genuine tags unreadable. Any future tightening of
 * the codec has to keep passing these, or the app silently loses the ability to read Creality's own
 * tags — which is exactly the bug this file was written in response to.
 *
 * Recorded verbatim from the diagnostic dump:
 * ```
 * Part 1 hex: 33 41 39 32 35 30 32 37 36 41 32 31 30 31 30 30 31 30 43 31 32 45 31 46
 *             30 31 36 35 30 30 30 30 30 31 30 30 30 30 30 30 76 00 00 00 00 00 00 00
 * Part 2 hex: 00 × 48
 * ```
 */
class GenuineCrealityTagTest {

    /** Written as an escape so no literal NUL byte ends up in this source file. */
    private val NUL = "\u0000"

    /** `[34,48)`: six ASCII zeros, then `0x76`, then seven NULs. Not the all-zeros the spec claims. */
    private val reserve = "000000v" + NUL.repeat(7)

    /** Sector 2 on this tag is entirely zero. */
    private val sectorTwo = NUL.repeat(48)

    private val payload = "3A925" + "0276" + "A2" + "101001" + "0C12E1F" + "0165" + "000001" +
        reserve + sectorTwo

    @Test
    fun `reconstructed payload is the right length`() {
        // Guards the fixture: a wrong-length literal would make every assertion below meaningless.
        assertEquals(TagCodec.PAYLOAD_LENGTH, payload.length)
    }

    /** The whole point: the original decoder rejected this tag outright. */
    @Test
    fun `a genuine Creality tag decodes`() {
        val decoded = TagCodec.decode(payload)

        assertEquals("01001", decoded.fields.filamentCatalogId)
        assertEquals("C12E1F", decoded.fields.colorRgb)
        assertEquals(WeightBucket.G500, decoded.fields.weight)
    }

    /**
     * This tag names no Spoolman spool: its reserve is `000000`, which the printer reads as "no ID"
     * and resolves by material and colour instead (TAG_FORMAT_SPEC.md §9). The `000001` in the serial
     * is the spool's own number and must not be reported as spool 1 — that is a different spool in
     * anyone's Spoolman, and the printer would never select it from this tag.
     */
    @Test
    fun `a genuine tag's serial is not mistaken for its spool id`() {
        val decoded = TagCodec.decode(payload)

        assertEquals(0, decoded.fields.spoolmanSpoolId)
        assertEquals("000001", decoded.serialNumber)
    }

    /**
     * The `[0,17)` partition: batch `[0,3)`, date `[3,8)`, supplier `[8,12)`, material `[12,17)`.
     *
     * Note what this partition implies: **no field equals `0276`.** The widely-repeated claim that
     * Creality's vendor code is `0276` describes an artifact of the date field ending `027` meeting a
     * supplier ID starting `6`. Pinned here because it is a surprising consequence that a future
     * reader might otherwise "fix" back to the wrong model.
     */
    @Test
    fun `fields split per the corrected partition`() {
        val decoded = TagCodec.decode(payload)

        assertEquals("3A9", decoded.fields.batchNumber)
        assertEquals("25027", decoded.fields.dateCode)
        assertEquals("6A21", decoded.fields.supplierId)
        assertEquals("01001", decoded.fields.filamentCatalogId)
    }

    /**
     * The date code is preserved verbatim, not parsed. `25027` does not parse as `YYMDD` with a
     * 1-indexed month — the month position holds `0` — so committing to a date encoding on this
     * evidence would be a guess. It reads as 27 January 2025 under either a 0-indexed month or a
     * day-of-year reading, and two samples cannot distinguish them.
     */
    @Test
    fun `date code is preserved verbatim rather than parsed`() {
        val decoded = TagCodec.decode(payload)

        assertEquals("25027", decoded.fields.dateCode)
        assertEquals('0', decoded.fields.dateCode[2])
    }

    /** Surfaced verbatim, including the `0x76`, so its meaning can be investigated later. */
    @Test
    fun `reserve field is surfaced verbatim including non-ascii bytes`() {
        assertEquals(reserve, TagCodec.decode(payload).reserve)
    }

    @Test
    fun `re-encoding a decoded genuine tag preserves its batch and date`() {
        val reEncoded = TagCodec.encode(TagCodec.decode(payload).fields)

        assertEquals("3A925", reEncoded.take(5))
        assertEquals("3A9250276A2101001", reEncoded.take(17))
    }

    /**
     * Documents what this app would *change* if it rewrote this tag with its own decoded fields: the
     * `0x76` byte is lost, sector 2 gains space padding, and the serial is rewritten from the spool
     * ID the reserve carries — here `000000`, so the tag's own `000001` does not survive a full
     * rewrite. All three are deviations from genuine content and all are flagged for hardware
     * validation; this test pins the current behaviour so a change to it is deliberate rather than
     * accidental. Keeping the serial is exactly what the ID-only overwrite is for (DESIGN.md DEC-08).
     */
    @Test
    fun `rewriting a genuine tag replaces the reserve byte and pads sector 2`() {
        val decoded = TagCodec.decode(payload).fields

        val reEncoded = TagCodec.encode(decoded)

        assertEquals("000000", reEncoded.substring(28, 34))
        assertEquals("00000000000000", reEncoded.substring(34, 48))
        assertEquals(" ".repeat(48), reEncoded.substring(48, 96))
    }

    /**
     * The ID-only overwrite on a genuine tag — the flow this tag class exists for. The spool the user
     * chose must be what a read then reports, while the tag's own serial and its `0x76` survive.
     */
    @Test
    fun `an id-only overwrite of a genuine tag reads back as the new spool`() {
        val decoded = TagCodec.decode(TagCodec.withSpoolId(payload, 42))

        assertEquals(42, decoded.fields.spoolmanSpoolId)
        assertEquals("000001", decoded.serialNumber)
        assertEquals("000042v" + NUL.repeat(7), decoded.reserve)
    }

    /** A round-trip of this app's own output must still be lossless. */
    @Test
    fun `our own payloads still round-trip unchanged`() {
        val ours = MappedFields(
            filamentCatalogId = "01001",
            colorRgb = "FF0000",
            weight = WeightBucket.G1000,
            spoolmanSpoolId = 42,
        )

        assertEquals(ours, TagCodec.decode(TagCodec.encode(ours)).fields)
    }

    /**
     * This app writes the community-proven constants rather than copying a genuine tag's batch/date or
     * computing a date it cannot verify.
     */
    @Test
    fun `our writes use the proven default batch date and supplier`() {
        val ours = MappedFields(
            filamentCatalogId = "01001",
            colorRgb = "FF0000",
            weight = WeightBucket.G1000,
            spoolmanSpoolId = 42,
        )

        assertEquals(MappedFields.DEFAULT_BATCH_NUMBER, ours.batchNumber)
        assertEquals(MappedFields.DEFAULT_DATE_CODE, ours.dateCode)
        assertEquals(MappedFields.CREALITY_SUPPLIER_ID, ours.supplierId)
        assertEquals("AB1240276A2101001", TagCodec.encode(ours).take(17))
    }
}
