package ch.jeanrichard.nfcspoolwriter.data.materials

import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource
import ch.jeanrichard.nfcspoolwriter.testsupport.bundledMaterialCatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the user's changes combine with the built-in list. */
class MaterialCatalogMergeTest {

    private val builtIn = MaterialCatalog.parseBuiltIn(bundledMaterialCatalogJson())

    private val custom = MaterialEntry(id = "20001", name = "Sunlu PLA+", brand = "Sunlu", type = "PLA")

    @Test
    fun `no overlay reproduces the built-in list unchanged`() {
        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay.EMPTY)

        assertEquals(builtIn.map { it.id }, merged.all.map { it.id })
        assertTrue(merged.all.all { it.source == MaterialSource.BUILT_IN })
        assertFalse(merged.hasUserChanges)
    }

    @Test
    fun `custom entries follow the built-in ones and are marked as added`() {
        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(custom = listOf(custom)))

        val last = merged.all.last()
        assertEquals("20001", last.id)
        assertEquals(MaterialSource.CUSTOM, last.source)
        assertEquals(builtIn.size + 1, merged.all.size)
        assertTrue(merged.hasUserChanges)
    }

    @Test
    fun `an override replaces the built-in entry in place`() {
        val edited = MaterialEntry(id = "17001", name = "CR-Wood", brand = "Creality", type = "PLA")

        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(overrides = mapOf("17001" to edited)))

        val entry = merged.findById("17001")!!
        assertEquals("PLA", entry.type)
        assertEquals(MaterialSource.EDITED, entry.source)
        assertEquals(builtIn.indexOfFirst { it.id == "17001" }, merged.all.indexOf(entry))
        assertEquals(builtIn.size, merged.all.size)
    }

    /** The key decides which built-in is replaced; a stale id inside the value must not fork it. */
    @Test
    fun `an override keeps the id it is stored under`() {
        val edited = MaterialEntry(id = "99999", name = "Renamed", brand = "Creality")

        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(overrides = mapOf("17001" to edited)))

        assertEquals("Renamed", merged.findById("17001")?.name)
        assertNull(merged.findById("99999"))
    }

    @Test
    fun `an override for an id that is not built in is ignored`() {
        val merged = MaterialCatalog.merged(
            builtIn,
            MaterialOverlay(overrides = mapOf("20001" to custom)),
        )

        assertNull(merged.findById("20001"))
        assertFalse(merged.hasUserChanges)
    }

    /** A newer app shipping an ID the user had added themselves: the firmware's meaning wins. */
    @Test
    fun `a custom entry colliding with a built-in id is dropped`() {
        val collision = custom.copy(id = "00001", name = "My PLA")

        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(custom = listOf(collision)))

        assertEquals("Generic PLA", merged.findById("00001")?.name)
        assertEquals(builtIn.size, merged.all.size)
    }

    @Test
    fun `custom entries take part in matching`() {
        val merged = MaterialCatalog.merged(
            builtIn,
            MaterialOverlay(custom = listOf(custom.copy(type = "PEEK"))),
        )

        assertEquals("20001", merged.findAllByType("PEEK").single().id)
        assertEquals("20001", merged.findByExactName("sunlu pla+")?.id)
    }

    @Test
    fun `an edited family changes matching`() {
        val edited = MaterialEntry(id = "17001", name = "CR-Wood", brand = "Creality", type = "Wood")

        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(overrides = mapOf("17001" to edited)))

        assertEquals("17001", merged.findAllByType("Wood").single().id)
    }

    @Test
    fun `brands and families are distinct and in first-appearance order`() {
        val merged = MaterialCatalog.merged(builtIn, MaterialOverlay(custom = listOf(custom)))

        assertEquals(listOf("Generic", "Creality", "Soleyin", "Sunlu"), merged.brands)
        assertEquals("PLA", merged.families.first())
        assertEquals(merged.families.size, merged.families.toSet().size)
        assertFalse(merged.families.contains(""))
    }

    @Test
    fun `values ignores source`() {
        assertEquals(custom.values(), custom.copy(source = MaterialSource.CUSTOM).values())
    }

    @Test
    fun `isValidId requires exactly five digits`() {
        assertTrue(MaterialEntry.isValidId("00001"))
        assertFalse(MaterialEntry.isValidId("0001"))
        assertFalse(MaterialEntry.isValidId("000001"))
        assertFalse(MaterialEntry.isValidId("0000a"))
        assertFalse(MaterialEntry.isValidId(""))
    }
}
