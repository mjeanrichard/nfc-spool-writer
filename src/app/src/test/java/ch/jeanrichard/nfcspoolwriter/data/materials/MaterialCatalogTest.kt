package ch.jeanrichard.nfcspoolwriter.data.materials

import ch.jeanrichard.nfcspoolwriter.testsupport.bundledMaterialCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the **real bundled asset**, not a fixture. The catalog is data the app ships and the
 * printer depends on, so a typo in it is a real defect — a fixture would hide exactly the errors worth
 * catching.
 */
class MaterialCatalogTest {

    private val catalog = MaterialCatalog.fromJson(bundledMaterialCatalogJson())

    @Test
    fun `bundled asset parses`() {
        assertEquals(52, catalog.all.size)
    }

    @Test
    fun `every id is five digits and unique`() {
        val ids = catalog.all.map { it.id }

        assertTrue(ids.all { it.length == 5 && it.all(Char::isDigit) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `generic profiles are all present`() {
        assertEquals(27, catalog.generics.size)
        assertTrue(catalog.generics.all { it.isGeneric })
    }

    @Test
    fun `looks up by id`() {
        assertEquals("Generic HIPS", catalog.findById("00012")?.name)
        assertEquals("Hyper PLA", catalog.findById("01001")?.name)
    }

    @Test
    fun `unknown id returns null`() {
        assertNull(catalog.findById("99999"))
    }

    @Test
    fun `looks up generic by type`() {
        assertEquals("00001", catalog.findGenericByType("PLA")?.id)
        assertEquals("00012", catalog.findGenericByType("HIPS")?.id)
        assertEquals("00004", catalog.findGenericByType("ABS")?.id)
    }

    /** Spoolman is free text, so punctuation and case must not defeat a match. */
    @Test
    fun `type lookup ignores case and punctuation`() {
        assertEquals("00006", catalog.findGenericByType("pla-cf")?.id)
        assertEquals("00006", catalog.findGenericByType("PLA CF")?.id)
        assertEquals("00006", catalog.findGenericByType("pla_cf")?.id)
    }

    @Test
    fun `type lookup only returns generic profiles`() {
        // 01001 Hyper PLA is also type PLA, but automatic selection must prefer the generic.
        assertEquals("00001", catalog.findGenericByType("PLA")?.id)
    }

    @Test
    fun `finds branded entries by exact name`() {
        assertEquals("01001", catalog.findByExactName("Hyper PLA")?.id)
        assertEquals("06002", catalog.findByExactName("Hyper PETG")?.id)
    }

    @Test
    fun `exact name lookup does not match a partial name`() {
        assertNull(catalog.findByExactName("Hyper"))
        assertNull(catalog.findByExactName("PLA"))
    }

    /** The deprecated Hyper PLA must never be selected automatically. */
    @Test
    fun `deprecated entries are excluded from selection`() {
        assertNotNull(catalog.findById("01002"))
        assertTrue(catalog.selectable.none { it.id == "01002" })
        assertTrue(catalog.findAllByType("PLA").none { it.id == "01002" })
    }

    @Test
    fun `findAllByType lists generic first`() {
        val plas = catalog.findAllByType("PLA")

        assertTrue(plas.size > 1)
        assertEquals("00001", plas.first().id)
    }

    @Test
    fun `entries without a stated family have a null type`() {
        // CR-Wood is presumably filled PLA, but the catalog deliberately does not assert that.
        assertNull(catalog.findById("17001")?.type)
    }

    /** A null type must not swallow a null query and match everything. */
    @Test
    fun `type lookup with a blank query matches nothing`() {
        assertNull(catalog.findGenericByType(""))
    }
}
