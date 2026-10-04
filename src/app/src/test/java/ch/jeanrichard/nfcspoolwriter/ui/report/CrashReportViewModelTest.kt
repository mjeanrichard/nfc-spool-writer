package ch.jeanrichard.nfcspoolwriter.ui.report

import ch.jeanrichard.nfcspoolwriter.data.report.CrashLog
import ch.jeanrichard.nfcspoolwriter.data.report.ErrorReport
import ch.jeanrichard.nfcspoolwriter.testsupport.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @get:Rule
    val folder = TemporaryFolder()

    private val log by lazy { CrashLog(File(folder.root, "crash.txt"), appVersion = "1.2") }

    private fun viewModel() = CrashReportViewModel(log, ioDispatcher = testDispatcher)

    @Test
    fun `nothing is offered after a clean run`() {
        assertNull(viewModel().pending.value)
    }

    @Test
    fun `the previous run's crash is offered as a crash report`() {
        log.record(Thread("main"), RuntimeException("boom"))

        val pending = viewModel().pending.value!!

        assertEquals(ErrorReport.Kind.Crash, pending.kind)
        assertEquals(log.read(), pending.details)
    }

    @Test
    fun `answering forgets the crash, so it is not offered again`() {
        log.record(Thread("main"), RuntimeException("boom"))
        val vm = viewModel()

        vm.dismiss()

        assertNull(vm.pending.value)
        assertNull(log.read())
        assertNull(viewModel().pending.value)
    }
}
