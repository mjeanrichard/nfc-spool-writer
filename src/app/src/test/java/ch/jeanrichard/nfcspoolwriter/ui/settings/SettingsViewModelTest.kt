package ch.jeanrichard.nfcspoolwriter.ui.settings

import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.testsupport.InMemoryPreferencesDataStore
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import ch.jeanrichard.nfcspoolwriter.testsupport.fakeSpoolmanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun settings() = SettingsRepository(InMemoryPreferencesDataStore())

    private fun viewModel(
        settings: SettingsRepository = settings(),
        testError: SpoolmanError? = null,
    ) = SettingsViewModel(settings, fakeSpoolmanRepository(testError = testError))

    @Test
    fun `starts empty when nothing is configured`() = runTest {
        val state = viewModel().state.value

        assertEquals("", state.url)
        assertNull(state.savedUrl)
        assertTrue(state.loaded)
    }

    @Test
    fun `loads the stored url`() = runTest {
        val settings = settings()
        settings.setSpoolmanBaseUrl("http://spoolman.local:7912")

        val state = viewModel(settings).state.value

        assertEquals("http://spoolman.local:7912", state.url)
        assertEquals("http://spoolman.local:7912", state.savedUrl)
    }

    @Test
    fun `save persists the url`() = runTest {
        val settings = settings()
        val vm = viewModel(settings)

        vm.onUrlChange("http://spoolman.local:7912")
        vm.save()

        assertEquals("http://spoolman.local:7912", settings.spoolmanBaseUrl.first())
        assertTrue(vm.state.value.justSaved)
    }

    @Test
    fun `save is only offered when there are unsaved changes`() = runTest {
        val settings = settings()
        settings.setSpoolmanBaseUrl("http://a")
        val vm = viewModel(settings)

        assertEquals(false, vm.state.value.hasUnsavedChanges)

        vm.onUrlChange("http://b")

        assertTrue(vm.state.value.hasUnsavedChanges)
    }

    /** The stored form is normalized, so re-typing the same address isn't a pending change. */
    @Test
    fun `a trailing slash is not treated as an unsaved change`() = runTest {
        val settings = settings()
        settings.setSpoolmanBaseUrl("http://a")
        val vm = viewModel(settings)

        vm.onUrlChange("http://a/")

        assertEquals(false, vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `the saved confirmation can be dismissed`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("http://a")
        vm.save()

        vm.onSavedMessageShown()

        assertEquals(false, vm.state.value.justSaved)
    }

    // --- Test connection -------------------------------------------------------------------

    @Test
    fun `a successful test is reported`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("http://spoolman.local:7912")

        vm.testConnection()

        assertEquals(TestResult.Succeeded, vm.state.value.testResult)
        assertEquals(false, vm.state.value.testing)
    }

    @Test
    fun `a failed test shows the error's user message`() = runTest {
        val error = SpoolmanError.Unreachable("http://h", null)
        val vm = viewModel(testError = error)
        vm.onUrlChange("http://h")

        vm.testConnection()

        assertEquals(error.userMessage, (vm.state.value.testResult as TestResult.Failed).message)
    }

    /** Testing an unsaved address is the point — the user shouldn't have to commit it first. */
    @Test
    fun `tests the address in the field without saving it`() = runTest {
        val settings = settings()
        val vm = viewModel(settings)
        vm.onUrlChange("http://unsaved.local")

        vm.testConnection()

        assertTrue(vm.state.value.testResult is TestResult.Succeeded)
        assertNull("must not have been saved", settings.spoolmanBaseUrl.first())
    }

    @Test
    fun `testing an empty address asks for one instead of calling out`() = runTest {
        val vm = viewModel()

        vm.testConnection()

        val result = vm.state.value.testResult
        assertTrue(result is TestResult.Failed)
        assertTrue((result as TestResult.Failed).message.contains("address"))
    }

    /** A stale pass/fail next to an edited address would be actively misleading. */
    @Test
    fun `editing the url clears a previous test result`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("http://a")
        vm.testConnection()
        assertTrue(vm.state.value.testResult != null)

        vm.onUrlChange("http://b")

        assertNull(vm.state.value.testResult)
    }
}
