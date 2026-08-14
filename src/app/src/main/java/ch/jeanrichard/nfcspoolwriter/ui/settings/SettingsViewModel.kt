package ch.jeanrichard.nfcspoolwriter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spoolman server configuration.
 *
 * Saving and testing are separate actions on purpose: a user can save an address they cannot currently
 * reach (server switched off, away from home) without the app treating that as invalid, and can test an
 * address before committing to it.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val spoolmanRepository: SpoolmanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settingsRepository.spoolmanBaseUrl.first()
            _state.update { it.copy(url = saved.orEmpty(), savedUrl = saved, loaded = true) }
        }
    }

    fun onUrlChange(url: String) = _state.update {
        // Any edit invalidates a previous test result and the saved confirmation — both referred
        // to a different address than the one now in the field.
        it.copy(url = url, testResult = null, justSaved = false)
    }

    fun save() {
        val url = _state.value.url
        viewModelScope.launch {
            settingsRepository.setSpoolmanBaseUrl(url)
            val saved = settingsRepository.spoolmanBaseUrl.first()
            _state.update { it.copy(savedUrl = saved, justSaved = true) }
        }
    }

    /** Tests the address currently in the field, saved or not. */
    fun testConnection() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) {
            _state.update { it.copy(testResult = TestResult.Failed(EMPTY_URL_MESSAGE)) }
            return
        }
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val result = when (val outcome = spoolmanRepository.testConnection(url)) {
                is SpoolmanResult.Success -> TestResult.Succeeded
                is SpoolmanResult.Failure -> TestResult.Failed(outcome.error.userMessage)
            }
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    private companion object {
        const val EMPTY_URL_MESSAGE = "Enter a server address first."
    }
}

data class SettingsUiState(
    val url: String = "",
    /** What is persisted, so the UI can show whether the field has unsaved edits. */
    val savedUrl: String? = null,
    /** False until the stored value has been read, so the field isn't briefly blank. */
    val loaded: Boolean = false,
    val testing: Boolean = false,
    val testResult: TestResult? = null,
    val justSaved: Boolean = false,
) {
    val hasUnsavedChanges: Boolean get() = loaded && url.trim().trimEnd('/') != savedUrl.orEmpty()
}

sealed interface TestResult {
    data object Succeeded : TestResult
    data class Failed(val message: String) : TestResult
}
