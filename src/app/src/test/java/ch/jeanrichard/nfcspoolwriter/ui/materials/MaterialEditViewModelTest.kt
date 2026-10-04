package ch.jeanrichard.nfcspoolwriter.ui.materials

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.inMemoryMaterialCatalogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MaterialEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val custom = MaterialEntry(id = "20001", name = "Sunlu PLA+", brand = "Sunlu", type = "PLA")

    private fun viewModel(
        materialId: String?,
        materials: MaterialCatalogRepository = inMemoryMaterialCatalogRepository(),
    ) = MaterialEditViewModel(materialId, materials)

    private suspend fun MaterialCatalogRepository.entry(id: String) = catalog.first().findById(id)

    // --- Loading -------------------------------------------------------------------------

    @Test
    fun `adding starts with an empty editable form`() = runTest {
        val state = viewModel(null).state.value

        assertTrue(state.loaded)
        assertTrue(state.isNew)
        assertTrue(state.idEditable)
        assertEquals("", state.id)
        assertNull(state.source)
        assertFalse(state.canDelete)
        assertFalse(state.canRevert)
        assertTrue(state.brandSuggestions.contains("Creality"))
        assertTrue(state.familySuggestions.contains("PETG"))
    }

    @Test
    fun `editing a built-in entry loads it with a fixed id`() = runTest {
        val state = viewModel("17001").state.value

        assertEquals("CR-Wood", state.name)
        assertEquals("Creality", state.brand)
        assertEquals("", state.type)
        assertEquals(MaterialSource.BUILT_IN, state.source)
        assertFalse(state.idEditable)
        assertFalse(state.canDelete)
        assertFalse(state.canRevert)
    }

    @Test
    fun `editing a custom entry allows renumbering and deleting`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)

        val state = viewModel("20001", materials).state.value

        assertEquals("Sunlu PLA+", state.name)
        assertTrue(state.idEditable)
        assertTrue(state.canDelete)
        assertFalse(state.canRevert)
    }

    @Test
    fun `an edited built-in entry can be reverted`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(materials.entry("17001")!!.copy(type = "PLA"))

        val state = viewModel("17001", materials).state.value

        assertEquals("PLA", state.type)
        assertTrue(state.canRevert)
        assertFalse(state.idEditable)
    }

    @Test
    fun `a deprecated entry loads its flag`() = runTest {
        assertTrue(viewModel("01002").state.value.deprecated)
    }

    @Test
    fun `an unknown id is reported as missing`() = runTest {
        val state = viewModel("99999").state.value

        assertTrue(state.loaded)
        assertTrue(state.missing)
    }

    // --- Validation ----------------------------------------------------------------------

    @Test
    fun `save refuses a malformed id`() = runTest {
        val vm = viewModel(null)
        vm.onIdChange("123")
        vm.onNameChange("X")
        vm.onBrandChange("Y")

        vm.save()

        assertEquals(FieldError.IdFormat, vm.state.value.idError)
        assertFalse(vm.state.value.done)
    }

    @Test
    fun `save refuses an id already in use and names the holder`() = runTest {
        val vm = viewModel(null)
        vm.onIdChange("00001")
        vm.onNameChange("X")
        vm.onBrandChange("Y")

        vm.save()

        assertEquals(FieldError.IdTaken("Generic PLA"), vm.state.value.idError)
    }

    @Test
    fun `save requires a name and a brand`() = runTest {
        val vm = viewModel(null)
        vm.onIdChange("20001")

        vm.save()

        assertEquals(FieldError.Required, vm.state.value.nameError)
        assertEquals(FieldError.Required, vm.state.value.brandError)
        assertTrue(vm.state.value.hasErrors)
    }

    @Test
    fun `editing a field clears its error`() = runTest {
        val vm = viewModel(null)
        vm.save()
        assertTrue(vm.state.value.hasErrors)

        vm.onIdChange("20001")
        vm.onNameChange("X")
        vm.onBrandChange("Y")

        assertFalse(vm.state.value.hasErrors)
    }

    @Test
    fun `an entry keeps its own id without a clash`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel("20001", materials)

        vm.onNameChange("Sunlu PLA+ Matte")
        vm.save()

        assertTrue(vm.state.value.done)
        assertEquals("Sunlu PLA+ Matte", materials.entry("20001")?.name)
    }

    // --- Saving --------------------------------------------------------------------------

    @Test
    fun `save adds a new custom entry with trimmed values`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        val vm = viewModel(null, materials)
        vm.onIdChange(" 20001 ")
        vm.onNameChange(" Sunlu PLA+ ")
        vm.onBrandChange("Sunlu ")
        vm.onTypeChange(" PLA ")
        vm.onDeprecatedChange(true)

        vm.save()

        assertTrue(vm.state.value.done)
        val saved = materials.entry("20001")!!
        assertEquals(MaterialEntry("20001", "Sunlu PLA+", "Sunlu", "PLA", deprecated = true), saved.values())
        assertEquals(MaterialSource.CUSTOM, saved.source)
    }

    @Test
    fun `a blank family is stored as none`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        val vm = viewModel(null, materials)
        vm.onIdChange("20001")
        vm.onNameChange("Mystery")
        vm.onBrandChange("Sunlu")
        vm.onTypeChange("  ")

        vm.save()

        assertNull(materials.entry("20001")?.type)
    }

    @Test
    fun `save on a built-in entry stores an override`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        val vm = viewModel("17001", materials)

        vm.onTypeChange("PLA")
        vm.save()

        assertEquals("PLA", materials.entry("17001")?.type)
        assertEquals(MaterialSource.EDITED, materials.entry("17001")?.source)
    }

    @Test
    fun `renumbering a custom entry moves it`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel("20001", materials)

        vm.onIdChange("20002")
        vm.save()

        assertNull(materials.entry("20001"))
        assertEquals("Sunlu PLA+", materials.entry("20002")?.name)
    }

    // --- Deleting and reverting ----------------------------------------------------------

    @Test
    fun `delete asks first and does nothing when cancelled`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel("20001", materials)

        vm.requestDelete()
        assertTrue(vm.state.value.confirmingDelete)
        vm.cancelDelete()

        assertFalse(vm.state.value.confirmingDelete)
        assertFalse(vm.state.value.done)
        assertEquals("Sunlu PLA+", materials.entry("20001")?.name)
    }

    @Test
    fun `confirming delete removes the entry and closes`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel("20001", materials)
        vm.requestDelete()

        vm.confirmDelete()

        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.confirmingDelete)
        assertNull(materials.entry("20001"))
    }

    @Test
    fun `revert restores the shipped values and closes`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(materials.entry("17001")!!.copy(type = "PLA"))
        val vm = viewModel("17001", materials)

        vm.revert()

        assertTrue(vm.state.value.done)
        assertNull(materials.entry("17001")?.type)
    }

    @Test
    fun `delete and revert do nothing while adding`() = runTest {
        val vm = viewModel(null)

        vm.confirmDelete()
        vm.revert()

        assertFalse(vm.state.value.done)
    }
}
