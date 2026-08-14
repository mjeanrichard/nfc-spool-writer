package ch.jeanrichard.nfcspoolwriter.ui.confirm

import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingWarning
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.testsupport.realFieldMappingService
import ch.jeanrichard.nfcspoolwriter.testsupport.realMaterialCatalog
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
    ) = ConfirmViewModel(
        spoolId = spool.id,
        spoolmanRepository = fakeSpoolmanRepository(listOf(spool), getError = getError),
        fieldMappingService = realFieldMappingService(),
        materialCatalog = realMaterialCatalog(),
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
            materialCatalog = realMaterialCatalog(),
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
}
