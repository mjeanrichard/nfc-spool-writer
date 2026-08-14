package ch.jeanrichard.nfcspoolwriter.domain.tagcodec

import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TagCodecTest {

    /** The spool from TAG_FORMAT_SPEC.md §10's full worked example. */
    private val specExample = MappedFields(
        filamentCatalogId = "01001",
        colorRgb = "FF0000",
        weight = WeightBucket.G1000,
        spoolmanSpoolId = 42,
    )

    @Test
    fun `encodes the spec's worked example`() {
        val encoded = TagCodec.encode(specExample)

        assertEquals("AB1240276A21010010FF0000033000004200004200000000", encoded.take(48))
        assertEquals(" ".repeat(48), encoded.drop(48))
    }

    @Test
    fun `encodes to exactly 96 characters`() {
        assertEquals(TagCodec.PAYLOAD_LENGTH, TagCodec.encode(specExample).length)
    }

    @Test
    fun `round-trips the spec's worked example`() {
        val decoded = TagCodec.decode(TagCodec.encode(specExample))

        assertEquals(specExample, decoded.fields)
    }

    @Test
    fun `reserve duplicates the padded spool id then zero-fills`() {
        val decoded = TagCodec.decode(TagCodec.encode(specExample))

        assertEquals("00004200000000", decoded.reserve)
    }

    @Test
    fun `serial number is zero-padded to six digits`() {
        val encoded = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))

        assertEquals("000007", encoded.substring(28, 34))
        assertEquals("00000700000000", encoded.substring(34, 48))
    }

    @Test
    fun `handles the maximum six-digit spool id without overflowing its field`() {
        val encoded = TagCodec.encode(specExample.copy(spoolmanSpoolId = 999_999))

        assertEquals(TagCodec.PAYLOAD_LENGTH, encoded.length)
        assertEquals("999999", encoded.substring(28, 34))
        assertEquals("99999900000000", encoded.substring(34, 48))
        assertEquals(999_999, TagCodec.decode(encoded).fields.spoolmanSpoolId)
    }

    @Test
    fun `handles spool id zero`() {
        val encoded = TagCodec.encode(specExample.copy(spoolmanSpoolId = 0))

        assertEquals("000000", encoded.substring(28, 34))
        assertEquals(0, TagCodec.decode(encoded).fields.spoolmanSpoolId)
    }

    @Test
    fun `rejects a spool id too large for the serial field`() {
        // Guarded by MappedFields so an over-long ID can never reach the codec and silently truncate.
        assertThrows(IllegalArgumentException::class.java) {
            specExample.copy(spoolmanSpoolId = 1_000_000)
        }
    }

    @Test
    fun `every weight bucket round-trips`() {
        WeightBucket.entries.forEach { bucket ->
            val decoded = TagCodec.decode(TagCodec.encode(specExample.copy(weight = bucket)))

            assertEquals(bucket, decoded.fields.weight)
        }
    }

    @Test
    fun `normalizes lowercase colour so a round-trip compares equal`() {
        val encoded = TagCodec.encode(specExample.copy(colorRgb = "ff00aa"))

        assertEquals("0FF00AA", encoded.substring(17, 24))
        assertEquals("FF00AA", TagCodec.decode(encoded).fields.colorRgb)
    }

    @Test
    fun `rejects a wrong-length payload`() {
        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode("too short") }

        assertEquals(true, error.message!!.contains("96 characters"))
    }

    /**
     * The re-partition of `[0,17)` must not change a single byte of output. These defaults reproduce
     * `AB1` + `24027` + `6A21` = the `AB1240276A21` prefix every community implementation writes and
     * that printers are reported to accept, so the new field model is provably output-compatible with
     * the old one.
     */
    @Test
    fun `default fields reproduce the community-proven prefix byte for byte`() {
        assertEquals("AB1240276A2101001", TagCodec.encode(specExample).take(17))
    }

    /**
     * `[0,3)` and `[3,8)` both vary between real tags (a genuine Creality tag held batch `3A9` and
     * date `25027`), so unfamiliar values must decode and be preserved rather than rejected. Requiring
     * a fixed value here is what made real tags unreadable. See [GenuineCrealityTagTest].
     */
    @Test
    fun `accepts and preserves an unfamiliar batch number and date code`() {
        val other = "XX9" + "99999" + TagCodec.encode(specExample).drop(8)

        val decoded = TagCodec.decode(other)

        assertEquals("XX9", decoded.fields.batchNumber)
        assertEquals("99999", decoded.fields.dateCode)
    }

    /**
     * With no fixed literal to check, the weight-bucket code is the main structural guard against
     * interpreting noise as a payload — a blank tag's sector 1 decrypts to garbage.
     */
    @Test
    fun `rejects a payload that is entirely noise`() {
        val noise = "Q".repeat(TagCodec.PAYLOAD_LENGTH)

        assertThrows(TagDecodeException::class.java) { TagCodec.decode(noise) }
    }

    @Test
    fun `rejects an unrecognized weight-bucket code`() {
        val encoded = TagCodec.encode(specExample)
        val tampered = encoded.replaceRange(24, 28, "9999")

        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }

        assertEquals(true, error.message!!.contains("9999"))
    }

    /**
     * Nothing resolves a spool from the serial (TAG_FORMAT_SPEC.md §9), so rejecting a tag over what
     * it holds there would cost the user a readable tag for no gain. It is preserved verbatim
     * instead, exactly like the batch number and date code.
     */
    @Test
    fun `accepts and preserves a non-numeric serial number`() {
        val tampered = TagCodec.encode(specExample).replaceRange(28, 34, "AB!@#$")

        val decoded = TagCodec.decode(tampered)

        assertEquals("AB!@#$", decoded.serialNumber)
        assertEquals(42, decoded.fields.spoolmanSpoolId)
    }

    /** The reserve is the one field the app relies on reading, so it is the one field guarded. */
    @Test
    fun `rejects a non-numeric reserve spool id`() {
        val tampered = TagCodec.encode(specExample).replaceRange(34, 40, "AB!@#$")

        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }

        assertEquals(true, error.message!!.contains("reserve spool ID"))
    }

    /** `toInt` alone would accept a sign; six digits is what the field is (§9). */
    @Test
    fun `rejects a signed reserve spool id`() {
        val tampered = TagCodec.encode(specExample).replaceRange(34, 40, "-00042")

        assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }
    }

    // --- Serial and reserve naming different spools --------------------------------------------

    /**
     * The divergence [TagCodec.withSpoolId] creates on purpose: the serial still names the previous
     * spool. `[34, 40)` is what a printer resolves from and what the app must report, or every read,
     * lookup and overwrite summary would name a spool the printer never selects (DESIGN.md DEC-08).
     */
    @Test
    fun `decode takes the spool id from the reserve, not the serial`() {
        val diverged = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))
            .replaceRange(34, 40, "000042")

        val decoded = TagCodec.decode(diverged)

        assertEquals(42, decoded.fields.spoolmanSpoolId)
        assertEquals("000007", decoded.serialNumber)
    }

    /** The same divergence as produced by the real overwrite path rather than a hand-spliced string. */
    @Test
    fun `a payload rewritten by withSpoolId decodes as the new spool`() {
        val original = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))

        val decoded = TagCodec.decode(TagCodec.withSpoolId(original, 42))

        assertEquals(42, decoded.fields.spoolmanSpoolId)
        assertEquals("000007", decoded.serialNumber)
        assertEquals(7, TagCodec.decode(original).fields.spoolmanSpoolId)
    }

    /**
     * A genuine tag's shape: a real serial number and an empty reserve. `0` is "no ID" to the printer
     * (§9), and the app must report the same rather than presenting the serial as a spool number.
     */
    @Test
    fun `an empty reserve decodes as spool id zero however the serial reads`() {
        val genuineShape = TagCodec.encode(specExample)
            .replaceRange(28, 34, "913277")
            .replaceRange(34, 40, "000000")

        val decoded = TagCodec.decode(genuineShape)

        assertEquals(0, decoded.fields.spoolmanSpoolId)
        assertEquals("913277", decoded.serialNumber)
    }

    /** Re-encoding a diverged payload resolves the divergence: both fields take the reserve's ID. */
    @Test
    fun `re-encoding a diverged payload puts the reserve id in both fields`() {
        val diverged = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))
            .replaceRange(34, 40, "000042")

        val reEncoded = TagCodec.encode(TagCodec.decode(diverged).fields)

        assertEquals("000042", reEncoded.substring(28, 34))
        assertEquals("00004200000000", reEncoded.substring(34, 48))
    }

    @Test
    fun `rejects a non-numeric material id`() {
        val tampered = TagCodec.encode(specExample).replaceRange(12, 17, "ABCDE")

        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }

        assertEquals(true, error.message!!.contains("filamentCatalogId"))
    }

    @Test
    fun `rejects a colour missing its structural prefix`() {
        val tampered = TagCodec.encode(specExample).replaceRange(17, 18, "9")

        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }

        assertEquals(true, error.message!!.contains("colour"))
    }

    @Test
    fun `wraps field validation failures as a decode error`() {
        // Right shape, wrong content: the colour keeps its '0' prefix but is not hex.
        val tampered = TagCodec.encode(specExample).replaceRange(18, 24, "ZZZZZZ")

        val error = assertThrows(TagDecodeException::class.java) { TagCodec.decode(tampered) }

        assertEquals(true, error.message!!.contains("validation"))
    }

    // --- withSpoolId ------------------------------------------------------------------------

    @Test
    fun `withSpoolId replaces the reserve id and nothing else`() {
        val original = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))

        val respliced = TagCodec.withSpoolId(original, 42)

        assertEquals("000042", respliced.substring(34, 40))
        assertEquals(original.take(34), respliced.take(34))
        assertEquals(original.drop(40), respliced.drop(40))
    }

    /**
     * The serial number is left holding the previous ID: nothing resolves a spool from it, and it is a
     * genuine tag's own data, which this mode exists to keep (DESIGN.md DEC-08).
     */
    @Test
    fun `withSpoolId leaves the serial number untouched`() {
        val original = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))

        val respliced = TagCodec.withSpoolId(original, 42)

        assertEquals("000007", respliced.substring(28, 34))
        assertEquals("000007", TagCodec.decode(respliced).serialNumber)
    }

    /** Only `[40, 48)`, where a genuine tag's unidentified bytes sit, survives around the new ID. */
    @Test
    fun `withSpoolId keeps the reserve's trailing bytes`() {
        val original = TagCodec.encode(specExample.copy(spoolmanSpoolId = 7))
            .replaceRange(40, 48, "v" + "\u0000".repeat(7))

        val decoded = TagCodec.decode(TagCodec.withSpoolId(original, 42))

        assertEquals("000042v" + "\u0000".repeat(7), decoded.reserve)
    }

    @Test
    fun `withSpoolId zero-pads a short id`() {
        val respliced = TagCodec.withSpoolId(TagCodec.encode(specExample), 7)

        assertEquals("000007", respliced.substring(34, 40))
    }

    @Test
    fun `withSpoolId accepts the boundary ids`() {
        val encoded = TagCodec.encode(specExample)

        assertEquals("000000", TagCodec.withSpoolId(encoded, 0).substring(34, 40))
        assertEquals("999999", TagCodec.withSpoolId(encoded, 999_999).substring(34, 40))
    }

    @Test
    fun `withSpoolId rejects an id too large for the reserve field`() {
        val encoded = TagCodec.encode(specExample)

        assertThrows(IllegalArgumentException::class.java) {
            TagCodec.withSpoolId(encoded, 1_000_000)
        }
    }

    /** Splicing into bytes that cannot be read would leave the tag broken, only differently. */
    @Test
    fun `withSpoolId rejects a payload that does not decode`() {
        assertThrows(TagDecodeException::class.java) {
            TagCodec.withSpoolId("Q".repeat(TagCodec.PAYLOAD_LENGTH), 42)
        }
    }

    @Test
    fun `withSpoolId rejects a wrong-length payload`() {
        assertThrows(TagDecodeException::class.java) { TagCodec.withSpoolId("too short", 42) }
    }

    /**
     * Sector 2 holds no fields, and real tags disagree about what is in it — genuine Creality tags
     * leave it zero-filled, community-written ones put a model name there. Whatever it holds must
     * not affect decoding.
     */
    @Test
    fun `sector 2 content is ignored when decoding`() {
        val encoded = TagCodec.encode(specExample)
        val tampered = encoded.replaceRange(48, 96, "k2".padEnd(48, '\u0000'))

        assertEquals(TagCodec.decode(encoded).fields, TagCodec.decode(tampered).fields)
    }
}
