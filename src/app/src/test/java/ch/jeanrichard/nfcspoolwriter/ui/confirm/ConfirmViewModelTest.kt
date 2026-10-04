package ch.jeanrichard.nfcspoolwriter.ui.confirm

import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingWarning
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.realFieldMappingService
import ch.jeanrichard.nfcspoolwriter.testsupport.inMemoryMaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.testSpool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfirmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        spool: ch.jeanrichard.nfcspoolwriter.domain.model.Spool = testSpool(),
        getError: SpoolmanError? = null,
        materials: MaterialCatalogRepository = inMemoryMaterialCatalogRepository(),
    ) = ConfirmViewModel(
        spoolId = spool.id,
        spoolmanRepository = fakeSpoolmanRepository(listOf(spool), getError = getError),
        fieldMappingService = realFieldMappingService(materials),
        materialCatalogRepository = materials,
    )

    @Test
    fun `shows the mapped fields`() = runTest {
        val state = viewModel().state.value

        assertEquals(false, state.loading)
        val fields = state.fields!!
        assertEquals("00001", fields.filamentCatalogId)
        assertEquals("0000FF", fields.colorRgb)
        assertEquals(WeightBucket.G1000, fields.weight)
        assertEquals(42, fields.spoolmanSpoolId)
    }

    /** The user needs a name, not a five-digit code, to tell whether the material is right. */
    @Test
    fun `resolves the material id to a readable catalog name`() = runTest {
        assertEquals("Generic PLA", viewModel().state.value.materialName)
    }

    @Test
    fun `a clean mapping has no notes`() = runTest {
        assertEquals(emptyList<String>(), viewModel().state.value.notes)
    }

    /** The whole reason this screen exists: approximations must be visible before writing. */
    @Test
    fun `surfaces mapping approximations as notes`() = runTest {
        val state = viewModel(testSpool(material = "PLA+", name = null, fullWeight = 900.0)).state.value

        assertTrue(state.notes.any { it.contains("PLA+") })
        assertTrue(state.notes.any { it.contains("900") })
        assertTrue(state.canWrite)
    }

    @Test
    fun `a missing colour is reported`() = runTest {
        val state = viewModel(testSpool(colorHex = null)).state.value

        assertTrue(state.notes.any { it.contains("colour") })
    }

    @Test
    fun `a clean mapping has no warnings`() = runTest {
        assertEquals(emptyList<MappingWarning>(), viewModel().state.value.warnings)
    }

    /** Spool ID 1 is written as-is; the printer-side quirk is surfaced without blocking the write. */
    @Test
    fun `spool id 1 is warned about but still writable`() = runTest {
        val state = viewModel(testSpool(id = 1)).state.value

        assertEquals(listOf(MappingWarning.IGNORED_SPOOL_ID), state.warnings)
        assertTrue(state.canWrite)
    }

    @Test
    fun `writing is blocked when the material cannot be mapped`() = runTest {
        val state = viewModel(testSpool(material = "PEEK", name = null)).state.value

        assertEquals(false, state.canWrite)
        assertTrue(state.unmappableReason!!.contains("PEEK"))
        assertNull(state.fields)
    }

    /** Even when unmappable, showing which spool it was helps the user go fix it in Spoolman. */
    @Test
    fun `an unmappable spool is still identified`() = runTest {
        val state = viewModel(testSpool(material = "PEEK", name = null)).state.value

        assertEquals(42, state.spool?.id)
    }

    @Test
    fun `a fetch failure surfaces its user message`() = runTest {
        val error = SpoolmanError.Unreachable("http://h", null)

        val state = viewModel(getError = error).state.value

        assertEquals(error.userMessage, state.error)
        assertEquals(false, state.canWrite)
    }

    @Test
    fun `a deleted spool is reported clearly`() = runTest {
        val vm = ConfirmViewModel(
            spoolId = 999,
            spoolmanRepository = fakeSpoolmanRepository(listOf(testSpool(id = 1))),
            fieldMappingService = realFieldMappingService(),
            materialCatalogRepository = inMemoryMaterialCatalogRepository(),
        )

        assertEquals(SpoolmanError.SpoolNotFound(999).userMessage, vm.state.value.error)
    }

    @Test
    fun `retry reloads after a failure`() = runTest {
        val vm = viewModel()

        vm.load()

        assertEquals(false, vm.state.value.loading)
        assertTrue(vm.state.value.canWrite)
    }

    // --- Choosing a material by hand -------------------------------------------------------

    @Test
    fun `choosing a material re-maps the loaded spool`() = runTest {
        val vm = viewModel()

        vm.chooseMaterial("01001")

        val state = vm.state.value
        assertEquals("01001", state.fields?.filamentCatalogId)
        assertEquals("Hyper PLA", state.materialName)
        assertEquals("01001", state.chosenMaterialId)
        assertTrue(state.canWrite)
    }

    @Test
    fun `choosing a material unblocks an unmappable spool`() = runTest {
        val vm = viewModel(testSpool(material = "PEEK", name = null))

        vm.chooseMaterial("00021")

        val state = vm.state.value
        assertNull(state.unmappableReason)
        assertEquals("00021", state.fields?.filamentCatalogId)
        assertTrue(state.canWrite)
    }

    @Test
    fun `going back to automatic restores the match and its notes`() = runTest {
        val vm = viewModel(testSpool(material = "PLA+", name = null))
        vm.chooseMaterial("01001")
        assertEquals(emptyList<String>(), vm.state.value.notes)

        vm.chooseMaterial(null)

        val state = vm.state.value
        assertNull(state.chosenMaterialId)
        assertEquals("00001", state.fields?.filamentCatalogId)
        assertTrue(state.notes.any { it.contains("PLA+") })
    }

    @Test
    fun `a choice that no longer exists blocks the write`() = runTest {
        val vm = viewModel()

        vm.chooseMaterial("99999")

        val state = vm.state.value
        assertEquals(false, state.canWrite)
        assertNull(state.fields)
        assertTrue(state.unmappableReason!!.contains("99999"))
    }

    @Test
    fun `a choice made before the spool loads is ignored`() = runTest {
        val vm = viewModel(getError = SpoolmanError.NotConfigured)

        vm.chooseMaterial("01001")

        assertNull(vm.state.value.chosenMaterialId)
    }

    @Test
    fun `reloading keeps the choice`() = runTest {
        val vm = viewModel()
        vm.chooseMaterial("01001")

        vm.load()

        assertEquals("01001", vm.state.value.fields?.filamentCatalogId)
    }

    @Test
    fun `a custom material can be chosen`() = runTest {
        val materials = inMemoryMaterialCatalogRepository()
        materials.save(
            ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry(
                id = "20001", name = "Sunlu PLA+", brand = "Sunlu", type = "PLA",
            )
        )
        val vm = viewModel(materials = materials)

        vm.chooseMaterial("20001")

        assertEquals("Sunlu PLA+", vm.state.value.materialName)
        assertTrue(vm.state.value.materialChoices.suggested.any { it.id == "20001" })
    }

    // --- What the picker offers ------------------------------------------------------------

    @Test
    fun `choices put the matched family first, generic at its head`() = runTest {
        val choices = viewModel().state.value.materialChoices

        assertEquals("00001", choices.suggested.first().id)
        assertTrue(choices.suggested.all { it.type == "PLA" })
        assertTrue(choices.others.none { it.type == "PLA" })
        assertTrue(choices.others.any { it.id == "00003" })
    }

    @Test
    fun `choices never include deprecated entries`() = runTest {
        val choices = viewModel().state.value.materialChoices

        assertTrue((choices.suggested + choices.others).none { it.id == "01002" })
    }

    /** With no match at all, the Spoolman material string still decides the suggested group. */
    @Test
    fun `an unmappable spool suggests by its Spoolman material`() = runTest {
        val choices = viewModel(testSpool(material = "PEEK", name = null)).state.value.materialChoices

        assertEquals(emptyList<ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry>(), choices.suggested)
        assertTrue(choices.others.any { it.id == "00001" })
    }

    @Test
    fun `choices are empty before the spool loads`() = runTest {
        val choices = viewModel(getError = SpoolmanError.NotConfigured).state.value.materialChoices

        assertTrue(choices.suggested.isEmpty() && choices.others.isEmpty())
    }

    @Test
    fun `filter matches name, brand, family and id, case-insensitively`() = runTest {
        val choices = viewModel().state.value.materialChoices

        assertEquals(listOf("01001"), choices.filter("hyper pla").suggested.map { it.id })
        assertTrue(choices.filter("creality").others.isNotEmpty())
        assertTrue(choices.filter("hips").others.all { it.type == "HIPS" })
        assertEquals(listOf("00012"), choices.filter("00012").others.map { it.id })
    }

    @Test
    fun `a blank filter returns everything`() = runTest {
        val choices = viewModel().state.value.materialChoices

        assertEquals(choices, choices.filter("  "))
    }
}
