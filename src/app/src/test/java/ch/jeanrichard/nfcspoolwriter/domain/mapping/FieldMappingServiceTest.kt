package ch.jeanrichard.nfcspoolwriter.domain.mapping

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.domain.model.Vendor
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.testsupport.bundledMaterialCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldMappingServiceTest {

    private val service = FieldMappingService(
        MaterialMatcher(MaterialCatalog.fromJson(bundledMaterialCatalogJson()))
    )

    private fun spool(
        id: Int = 42,
        material: String? = "PLA",
        colorHex: String? = "FF0000",
        fullWeight: Double? = 1000.0,
        remaining: Double? = 400.0,
        name: String? = null,
    ) = Spool(
        id = id,
        remainingWeightGrams = remaining,
        filament = Filament(
            id = 7,
            name = name,
            vendor = Vendor(id = 3, name = "Polymaker"),
            material = material,
            colorHex = colorHex,
            fullSpoolWeightGrams = fullWeight,
        ),
    )

    private fun mapped(spool: Spool): MappingResult.Mapped =
        service.map(spool) as MappingResult.Mapped

    // --- Happy path ------------------------------------------------------------------------

    @Test
    fun `maps a straightforward spool`() {
        val fields = mapped(spool()).fields

        assertEquals("00001", fields.filamentCatalogId)
        assertEquals("FF0000", fields.colorRgb)
        assertEquals(WeightBucket.G1000, fields.weight)
        assertEquals(42, fields.spoolmanSpoolId)
    }

    @Test
    fun `a clean mapping produces no notes`() {
        assertEquals(emptyList<String>(), mapped(spool()).notes)
    }

    /** Decision: every spool gets Creality's supplier ID (DESIGN.md DEC-03). */
    @Test
    fun `all spools are written with Creality's supplier id`() {
        assertEquals(MappedFields.CREALITY_SUPPLIER_ID, mapped(spool()).fields.supplierId)
    }

    @Test
    fun `batch and date keep the proven constants`() {
        val fields = mapped(spool()).fields

        assertEquals(MappedFields.DEFAULT_BATCH_NUMBER, fields.batchNumber)
        assertEquals(MappedFields.DEFAULT_DATE_CODE, fields.dateCode)
    }

    // --- Weight ----------------------------------------------------------------------------

    /** The bucket is the spool's nominal size, so consumption must not change it. */
    @Test
    fun `weight uses the full spool weight not the remaining weight`() {
        val fields = mapped(spool(fullWeight = 1000.0, remaining = 120.0)).fields

        assertEquals(WeightBucket.G1000, fields.weight)
    }

    @Test
    fun `an off-bucket weight is rounded and reported`() {
        val result = mapped(spool(fullWeight = 900.0))

        assertEquals(WeightBucket.G1000, result.fields.weight)
        assertTrue(result.notes.any { it.contains("900") && it.contains("1000") })
    }

    @Test
    fun `a missing weight falls back to the default and is reported`() {
        val result = mapped(spool(fullWeight = null))

        assertEquals(FieldMappingService.DEFAULT_WEIGHT, result.fields.weight)
        assertTrue(result.notes.any { it.contains("no full-spool weight") })
    }

    @Test
    fun `a zero weight is treated as missing`() {
        val result = mapped(spool(fullWeight = 0.0))

        assertEquals(FieldMappingService.DEFAULT_WEIGHT, result.fields.weight)
    }

    @Test
    fun `exact bucket weights are not reported as rounded`() {
        assertEquals(emptyList<String>(), mapped(spool(fullWeight = 500.0)).notes)
    }

    // --- Colour ----------------------------------------------------------------------------

    @Test
    fun `a leading hash is stripped`() {
        assertEquals("00FF00", mapped(spool(colorHex = "#00FF00")).fields.colorRgb)
    }

    @Test
    fun `lowercase hex is normalized`() {
        assertEquals("ABCDEF", mapped(spool(colorHex = "abcdef")).fields.colorRgb)
    }

    @Test
    fun `an alpha channel is dropped and reported`() {
        val result = mapped(spool(colorHex = "11223344"))

        assertEquals("112233", result.fields.colorRgb)
        assertTrue(result.notes.any { it.contains("alpha") })
    }

    @Test
    fun `a three-digit colour is expanded`() {
        val result = mapped(spool(colorHex = "F0A"))

        assertEquals("FF00AA", result.fields.colorRgb)
        assertTrue(result.notes.any { it.contains("expanded") })
    }

    @Test
    fun `a missing colour falls back to the default and is reported`() {
        val result = mapped(spool(colorHex = null))

        assertEquals(FieldMappingService.DEFAULT_COLOUR, result.fields.colorRgb)
        assertTrue(result.notes.any { it.contains("no colour") })
    }

    @Test
    fun `a non-hex colour falls back to the default and is reported`() {
        val result = mapped(spool(colorHex = "ZZZZZZ"))

        assertEquals(FieldMappingService.DEFAULT_COLOUR, result.fields.colorRgb)
        assertTrue(result.notes.any { it.contains("not valid hex") })
    }

    @Test
    fun `an odd-length colour falls back to the default`() {
        assertEquals(
            FieldMappingService.DEFAULT_COLOUR,
            mapped(spool(colorHex = "FF00")).fields.colorRgb,
        )
    }

    // --- Material fallbacks surface to the user --------------------------------------------

    @Test
    fun `a material fallback is reported as a note`() {
        val result = mapped(spool(material = "PLA+"))

        assertEquals("00001", result.fields.filamentCatalogId)
        assertTrue(result.notes.any { it.contains("PLA+") })
    }

    @Test
    fun `an unmappable material makes the whole spool unmappable`() {
        val result = service.map(spool(material = "PEEK"))

        assertTrue("expected Unmappable, was $result", result is MappingResult.Unmappable)
        assertTrue((result as MappingResult.Unmappable).reason.contains("PEEK"))
    }

    /** Failing on material must not be masked by the other fields having valid defaults. */
    @Test
    fun `a spool with no material at all is unmappable`() {
        assertTrue(service.map(spool(material = null)) is MappingResult.Unmappable)
    }

    // --- Spool ID --------------------------------------------------------------------------

    @Test
    fun `spool id is carried through`() {
        assertEquals(999_999, mapped(spool(id = 999_999)).fields.spoolmanSpoolId)
    }

    /**
     * The serial field is 6 digits, so a larger Spoolman ID cannot be represented. Currently this
     * throws from [MappedFields]; documented here so the failure is a known boundary rather than a
     * surprise crash in the UI. Phase 5 should surface it as a mapping error.
     */
    @Test
    fun `a spool id beyond six digits is rejected rather than silently truncated`() {
        val error = runCatching { service.map(spool(id = 1_000_000)) }.exceptionOrNull()

        assertTrue("expected a failure, got none", error is IllegalArgumentException)
    }
}
