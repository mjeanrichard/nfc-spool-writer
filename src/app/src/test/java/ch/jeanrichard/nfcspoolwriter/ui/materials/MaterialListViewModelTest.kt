package ch.jeanrichard.nfcspoolwriter.ui.materials

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.inMemoryMaterialCatalogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MaterialListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val custom = MaterialEntry(id = "20001", name = "Sunlu PLA+", brand = "Sunlu", type = "PLA")

    private fun viewModel(
        materials: MaterialCatalogRepository = inMemoryMaterialCatalogRepository(),
    ) = MaterialListViewModel(materials)

    @Test
    fun `lists the whole catalog sorted by id, generic first`() = runTest {
        val state = viewModel().state.value
        val ids = state.visibleEntries.map { it.id }

        assertTrue(state.loaded)
        assertEquals(ids.sorted(), ids)
        assertEquals("Generic", state.visibleEntries.first().brand)
        assertFalse(state.hasUserChanges)
    }

    @Test
    fun `a custom entry takes its place by id`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom.copy(id = "00000"))

        val state = viewModel(materials).state.value

        assertEquals("00000", state.visibleEntries.first().id)
        assertTrue(state.hasUserChanges)
    }

    @Test
    fun `the list follows catalog changes`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        val vm = viewModel(materials)

        materials.save(custom)

        assertTrue(vm.state.value.entries.any { it.id == "20001" })
    }

    @Test
    fun `search filters on name brand family and id`() = runTest {
        val vm = viewModel()

        vm.onQueryChange("hips")
        assertEquals(listOf("00012"), vm.state.value.visibleEntries.map { it.id })

        vm.onQueryChange("soleyin")
        assertEquals(setOf("Soleyin"), vm.state.value.visibleEntries.map { it.brand }.toSet())

        vm.onQueryChange("06002")
        assertEquals(listOf("Hyper PETG"), vm.state.value.visibleEntries.map { it.name })

        vm.onQueryChange("pla-cf")
        assertTrue(vm.state.value.visibleEntries.all { it.type == "PLA-CF" })
    }

    @Test
    fun `a search with no hits is reported as empty`() = runTest {
        val vm = viewModel()

        vm.onQueryChange("nothing like this")

        assertTrue(vm.state.value.isEmptyResult)
    }

    @Test
    fun `a blank search shows everything`() = runTest {
        val vm = viewModel()

        vm.onQueryChange("   ")

        assertFalse(vm.state.value.isEmptyResult)
        assertEquals(52, vm.state.value.visibleEntries.size)
    }

    @Test
    fun `not loaded is not an empty result`() {
        assertFalse(MaterialListUiState().isEmptyResult)
    }

    @Test
    fun `reset asks first and does nothing when cancelled`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel(materials)

        vm.requestReset()
        assertTrue(vm.state.value.confirmingReset)

        vm.cancelReset()

        assertFalse(vm.state.value.confirmingReset)
        assertTrue(materials.catalog.first().hasUserChanges)
    }

    @Test
    fun `confirming the reset drops every change`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(custom)
        val vm = viewModel(materials)
        vm.requestReset()

        vm.confirmReset()

        assertFalse(vm.state.value.confirmingReset)
        assertFalse(vm.state.value.hasUserChanges)
        assertFalse(materials.catalog.first().hasUserChanges)
    }
}
