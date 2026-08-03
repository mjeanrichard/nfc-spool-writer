package ch.jeanrichard.nfcspoolwriter.domain.mapping

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import ch.jeanrichard.nfcspoolwriter.testsupport.bundledMaterialCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialMatcherTest {

    private val matcher = MaterialMatcher(MaterialCatalog.fromJson(bundledMaterialCatalogJson()))

    private fun filament(material: String? = null, name: String? = null) =
        Filament(id = 1, name = name, material = material)

    private fun matchId(material: String?, name: String? = null): String? =
        matcher.match(filament(material, name)).entry?.id

    // --- Exact matches ---------------------------------------------------------------------

    @Test
    fun `common materials match their generic profile exactly`() {
        assertEquals("00001", matchId("PLA"))
        assertEquals("00003", matchId("PETG"))
        assertEquals("00004", matchId("ABS"))
        assertEquals("00005", matchId("TPU"))
        assertEquals("00007", matchId("ASA"))
        assertEquals("00012", matchId("HIPS"))
        assertEquals("00021", matchId("PC"))
    }

    @Test
    fun `an exact match is reported as exact`() {
        assertTrue(matcher.match(filament("PLA")) is MaterialMatch.Exact)
    }

    @Test
    fun `case and punctuation do not matter`() {
        assertEquals("00001", matchId("pla"))
        assertEquals("00006", matchId("pla cf"))
        assertEquals("00006", matchId("PLA_CF"))
    }

    /** A genuine Creality spool tracked in Spoolman should get its real profile, not the generic. */
    @Test
    fun `an exact catalog product name wins over the generic profile`() {
        assertEquals("01001", matchId(material = "PLA", name = "Hyper PLA"))
    }

    @Test
    fun `a non-catalog product name falls through to the material type`() {
        assertEquals("00001", matchId(material = "PLA", name = "PolyTerra PLA Blue"))
    }

    // --- Aliases ---------------------------------------------------------------------------

    @Test
    fun `nylon maps to PA`() {
        assertEquals("00008", matchId("Nylon"))
    }

    @Test
    fun `durometer-suffixed TPU maps to TPU`() {
        assertEquals("00005", matchId("TPU 95A"))
        assertEquals("00005", matchId("TPE"))
    }

    @Test
    fun `co-polyester maps to PETG`() {
        assertEquals("00003", matchId("CPE"))
    }

    @Test
    fun `silk maps to the PLA-Silk profile`() {
        assertEquals("00002", matchId("PLA-Silk"))
        assertEquals("00002", matchId("Silk"))
    }

    @Test
    fun `wood-filled PLA maps to PLA`() {
        assertEquals("00001", matchId("PLA-Wood"))
    }

    @Test
    fun `an alias match is reported as a fallback with a reason`() {
        val match = matcher.match(filament("Nylon"))

        assertTrue(match is MaterialMatch.Fallback)
        assertTrue((match as MaterialMatch.Fallback).reason.contains("Nylon"))
    }

    // --- Modifier stripping ----------------------------------------------------------------

    @Test
    fun `grade suffixes are stripped`() {
        assertEquals("00001", matchId("PLA+"))
        assertEquals("00001", matchId("PLA Plus"))
        assertEquals("00003", matchId("PETG Pro"))
        assertEquals("00004", matchId("ABS+"))
    }

    @Test
    fun `stripping is reported as a fallback`() {
        val match = matcher.match(filament("PLA+"))

        assertTrue(match is MaterialMatch.Fallback)
    }

    // --- Filler stripping ------------------------------------------------------------------

    /** The catalog has no glass-filled profiles, so drop the filler and keep the polymer. */
    @Test
    fun `glass-filled variants fall back to the unfilled polymer`() {
        assertEquals("00001", matchId("PLA-GF"))
        assertEquals("00003", matchId("PETG-GF"))
    }

    /** But a filled profile that *does* exist must be matched exactly, not reduced. */
    @Test
    fun `carbon-filled variants that exist are matched exactly`() {
        assertEquals("00006", matchId("PLA-CF"))
        assertEquals("00014", matchId("PETG-CF"))
        assertEquals("00018", matchId("PPS-CF"))
        assertTrue(matcher.match(filament("PLA-CF")) is MaterialMatch.Exact)
    }

    // --- No match --------------------------------------------------------------------------

    /**
     * The important behaviour: an unknown material must not be guessed at. A wrong material ID makes
     * the printer apply wrong temperatures with no warning to the user.
     */
    @Test
    fun `an unknown material is not guessed`() {
        val match = matcher.match(filament("PEEK"))

        assertTrue("expected NoMatch, was $match", match is MaterialMatch.NoMatch)
        assertEquals(null, match.entry)
    }

    @Test
    fun `other unprintable exotics also fail closed`() {
        listOf("PEI", "ULTEM", "PVB", "Moon Rock").forEach { material ->
            assertTrue(
                "$material should not match",
                matcher.match(filament(material)) is MaterialMatch.NoMatch,
            )
        }
    }

    @Test
    fun `a missing material is reported as no match`() {
        val match = matcher.match(filament(material = null))

        assertTrue(match is MaterialMatch.NoMatch)
        assertEquals(null, (match as MaterialMatch.NoMatch).requested)
    }

    @Test
    fun `a blank material is reported as no match`() {
        assertTrue(matcher.match(filament("   ")) is MaterialMatch.NoMatch)
    }

    @Test
    fun `no match carries the requested material for the error message`() {
        val match = matcher.match(filament("PEEK")) as MaterialMatch.NoMatch

        assertEquals("PEEK", match.requested)
        assertTrue(match.reason.contains("PEEK"))
    }

    /** A deprecated profile must never be selected, even via an exact name. */
    @Test
    fun `the deprecated Hyper PLA is never selected`() {
        val match = matcher.match(filament(material = "PLA", name = "Hyper PLA (deprecated)"))

        assertEquals("00001", match.entry?.id)
    }
}
