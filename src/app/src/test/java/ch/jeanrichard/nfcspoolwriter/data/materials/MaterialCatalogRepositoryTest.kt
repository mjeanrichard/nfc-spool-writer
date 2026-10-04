package ch.jeanrichard.nfcspoolwriter.data.materials

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource
import ch.jeanrichard.nfcspoolwriter.testsupport.InMemoryPreferencesDataStore
import ch.jeanrichard.nfcspoolwriter.testsupport.bundledMaterialCatalogJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MaterialCatalogRepositoryTest {

    private val builtIn = MaterialCatalog.parseBuiltIn(bundledMaterialCatalogJson())
    private lateinit var dataStore: InMemoryPreferencesDataStore
    private lateinit var repository: MaterialCatalogRepository

    private val custom = MaterialEntry(id = "20001", name = "Sunlu PLA+", brand = "Sunlu", type = "PLA")

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        repository = MaterialCatalogRepository(builtIn, dataStore)
    }

    private suspend fun catalog() = repository.catalog.first()

    private suspend fun storedJson(): String? =
        dataStore.data.first()[stringPreferencesKey("material_overlay")]

    @Test
    fun `starts as the built-in list`() = runTest {
        assertEquals(builtIn.size, catalog().all.size)
        assertFalse(catalog().hasUserChanges)
    }

    @Test
    fun `saving a new id adds a custom entry`() = runTest {
        repository.save(custom)

        val entry = catalog().findById("20001")!!
        assertEquals("Sunlu PLA+", entry.name)
        assertEquals(MaterialSource.CUSTOM, entry.source)
    }

    @Test
    fun `saving an existing custom id replaces it`() = runTest {
        repository.save(custom)
        repository.save(custom.copy(name = "Sunlu PLA+ Matte"))

        assertEquals(1, catalog().all.count { it.brand == "Sunlu" })
        assertEquals("Sunlu PLA+ Matte", catalog().findById("20001")?.name)
    }

    @Test
    fun `saving a custom entry under a new id drops the old one`() = runTest {
        repository.save(custom)
        repository.save(custom.copy(id = "20002"), replacingId = "20001")

        assertNull(catalog().findById("20001"))
        assertEquals("Sunlu PLA+", catalog().findById("20002")?.name)
    }

    @Test
    fun `saving a built-in id stores an override`() = runTest {
        repository.save(builtIn.first { it.id == "17001" }.copy(type = "PLA"))

        val entry = catalog().findById("17001")!!
        assertEquals("PLA", entry.type)
        assertEquals(MaterialSource.EDITED, entry.source)
        assertEquals(builtIn.size, catalog().all.size)
    }

    /** Editing a value back to what shipped is the same as reverting: no override lingers. */
    @Test
    fun `saving a built-in entry with its shipped values removes the override`() = runTest {
        val shipped = builtIn.first { it.id == "17001" }
        repository.save(shipped.copy(type = "PLA"))
        repository.save(shipped.copy(source = MaterialSource.EDITED))

        assertEquals(MaterialSource.BUILT_IN, catalog().findById("17001")?.source)
        assertNull(storedJson())
    }

    @Test
    fun `revert restores a built-in entry`() = runTest {
        repository.save(builtIn.first { it.id == "17001" }.copy(type = "PLA"))

        repository.revert("17001")

        val entry = catalog().findById("17001")!!
        assertNull(entry.type)
        assertEquals(MaterialSource.BUILT_IN, entry.source)
    }

    @Test
    fun `revert leaves a custom entry alone`() = runTest {
        repository.save(custom)

        repository.revert("20001")

        assertEquals("Sunlu PLA+", catalog().findById("20001")?.name)
    }

    @Test
    fun `delete removes a custom entry`() = runTest {
        repository.save(custom)

        repository.delete("20001")

        assertNull(catalog().findById("20001"))
        assertNull(storedJson())
    }

    @Test
    fun `delete leaves a built-in entry alone`() = runTest {
        repository.save(builtIn.first { it.id == "17001" }.copy(type = "PLA"))

        repository.delete("17001")

        assertEquals("PLA", catalog().findById("17001")?.type)
    }

    @Test
    fun `resetAll drops every change`() = runTest {
        repository.save(custom)
        repository.save(builtIn.first { it.id == "17001" }.copy(type = "PLA"))

        repository.resetAll()

        assertFalse(catalog().hasUserChanges)
        assertEquals(builtIn.size, catalog().all.size)
        assertNull(storedJson())
    }

    @Test
    fun `isBuiltIn tells built-in ids from the rest`() {
        assertTrue(repository.isBuiltIn("00001"))
        assertFalse(repository.isBuiltIn("20001"))
    }

    @Test
    fun `a malformed id is refused`() = runTest {
        val error = runCatching { repository.save(custom.copy(id = "2001")) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertNull(storedJson())
    }

    @Test
    fun `a built-in entry cannot be renumbered`() = runTest {
        val error = runCatching {
            repository.save(custom, replacingId = "00001")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    /** The overlay survives a round trip through its stored JSON form. */
    @Test
    fun `changes persist across repository instances`() = runTest {
        repository.save(custom)
        repository.save(builtIn.first { it.id == "17001" }.copy(type = "PLA"))

        val reopened = MaterialCatalogRepository(builtIn, dataStore).catalog.first()

        assertEquals("Sunlu PLA+", reopened.findById("20001")?.name)
        assertEquals("PLA", reopened.findById("17001")?.type)
    }

    @Test
    fun `source is not persisted`() = runTest {
        repository.save(custom.copy(source = MaterialSource.EDITED))

        assertFalse(storedJson()!!.contains("source"))
        assertEquals(MaterialSource.CUSTOM, catalog().findById("20001")?.source)
    }

    @Test
    fun `unreadable stored changes fall back to the built-in list`() = runTest {
        dataStore.edit { it[stringPreferencesKey("material_overlay")] = "{not json" }

        assertEquals(builtIn.size, catalog().all.size)
        assertFalse(catalog().hasUserChanges)
    }

    @Test
    fun `stored changes with the wrong shape fall back to the built-in list`() = runTest {
        dataStore.edit { it[stringPreferencesKey("material_overlay")] = """{"custom": 5}""" }

        assertFalse(catalog().hasUserChanges)
    }

    @Test
    fun `a blank stored value is treated as no changes`() = runTest {
        dataStore.edit { it[stringPreferencesKey("material_overlay")] = "  " }

        assertFalse(catalog().hasUserChanges)
    }

    /** A save over unreadable data must not crash; it starts a fresh overlay. */
    @Test
    fun `saving over unreadable stored changes starts afresh`() = runTest {
        dataStore.edit { it[stringPreferencesKey("material_overlay")] = "{not json" }

        repository.save(custom)

        assertEquals("Sunlu PLA+", catalog().findById("20001")?.name)
    }
}
