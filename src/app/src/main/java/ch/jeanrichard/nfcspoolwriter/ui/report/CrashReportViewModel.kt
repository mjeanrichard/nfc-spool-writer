package ch.jeanrichard.nfcspoolwriter.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.report.CrashLog
import ch.jeanrichard.nfcspoolwriter.data.report.ErrorReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offers the crash from the previous run, once.
 *
 * Whatever the user answers, the crash is then forgotten: a report that was declined is not asked
 * about again, and one that was sent now lives in the user's email app, where they can still change
 * their mind before it goes.
 */
class CrashReportViewModel(
    private val crashLog: CrashLog,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _pending = MutableStateFlow<ErrorReport?>(null)

    /** The previous run's crash, while the user has not yet answered about it. */
    val pending: StateFlow<ErrorReport?> = _pending.asStateFlow()

    init {
        viewModelScope.launch {
            _pending.value = withContext(ioDispatcher) { crashLog.read() }
                ?.let { ErrorReport(ErrorReport.Kind.Crash, it) }
        }
    }

    /** Called both when the report is sent and when it is declined. */
    fun dismiss() {
        _pending.value = null
        viewModelScope.launch(ioDispatcher) { crashLog.clear() }
    }
}
