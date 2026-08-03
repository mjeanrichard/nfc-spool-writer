package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.settings.SettingsRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanError
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Browse and search the Spoolman spool list.
 *
 * Searching filters the already-loaded list in memory rather than re-querying. Spoolman's filters are
 * per-field, so a single search box would otherwise need several round trips per keystroke and still
 * couldn't match across fields — see `SpoolmanRepository.loadAllSpools`.
 */
class SpoolListViewModel(
    private val spoolmanRepository: SpoolmanRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SpoolListUiState())
    val state: StateFlow<SpoolListUiState> = _state.asStateFlow()

    private var fetchJob: Job? = null

    init {
        // Loading follows the configured address rather than happening once on construction. This
        // screen is the start destination, so it stays in the back stack — and keeps this ViewModel —
        // while the user configures the server in settings: a fresh install would otherwise sit on
        // its "not configured" call to action until the user pulled to refresh. Later switches to a
        // different server land the same way.
        viewModelScope.launch {
            settingsRepository.spoolmanBaseUrl.distinctUntilChanged().collect { load() }
        }
    }

    /** First load, and the retry offered when there is nothing on screen: a full-screen spinner. */
    fun load() = fetch(refreshing = false)

    /**
     * Pull-to-refresh. The list, the search query and — if the reload fails — the spools already
     * loaded all stay on screen; only the indicator differs from [load].
     */
    fun refresh() = fetch(refreshing = true)

    private fun fetch(refreshing: Boolean) {
        _state.update {
            it.copy(loading = !refreshing, refreshing = refreshing, error = null, notConfigured = false)
        }
        // A load starts from a settings change as well as from the user, so two can overlap — drop
        // the older one rather than let its result land on top of the newer one's.
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            when (val result = spoolmanRepository.loadAllSpools()) {
                is SpoolmanResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        spools = result.value.spools,
                        // The repository caps how much it will collect; tell the user rather than
                        // silently showing a partial list.
                        truncated = result.value.totalCount > result.value.spools.size,
                        totalCount = result.value.totalCount,
                    )
                }

                is SpoolmanResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        // A failed refresh keeps the list the user was browsing — a momentary
                        // network blip shouldn't cost them it. A failed first load has none.
                        spools = if (refreshing) it.spools else emptyList(),
                        // "Nothing configured yet" is a first-run state with its own call to action,
                        // not a failure to report like a dead server.
                        notConfigured = result.error is SpoolmanError.NotConfigured,
                        error = result.error.userMessage
                            .takeIf { result.error !is SpoolmanError.NotConfigured },
                    )
                }
            }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }
}

data class SpoolListUiState(
    /** A load with nothing to show yet — a full-screen spinner. */
    val loading: Boolean = false,
    /** A reload with the list still on screen — the pull-to-refresh indicator. */
    val refreshing: Boolean = false,
    val spools: List<Spool> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val notConfigured: Boolean = false,
    val truncated: Boolean = false,
    val totalCount: Int = 0,
) {
    /**
     * Matches across every field a user might search by — material, vendor, filament name, location,
     * lot number, and the spool ID — because a single box that only searched one of them would feel
     * broken. All terms must match somewhere, so "polymaker pla" narrows rather than widens.
     */
    val visibleSpools: List<Spool> by lazy {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return@lazy spools
        spools.filter { spool ->
            val haystack = listOfNotNull(
                spool.id.toString(),
                spool.filament.name,
                spool.filament.material,
                spool.filament.vendor?.name,
                spool.location,
                spool.lotNumber,
            ).joinToString(" ").lowercase()
            terms.all { haystack.contains(it) }
        }
    }

    /**
     * Nothing to list. Not conditioned on [error]: a failed *refresh* keeps its spools and reports
     * itself alongside the list, so "no spools" and "a reload failed" can be true at once.
     */
    val isEmptyResult: Boolean
        get() = !loading && !notConfigured && visibleSpools.isEmpty()

    /** Distinguishes "no spools at all" from "nothing matches the search". */
    val hasNoSpoolsAtAll: Boolean get() = spools.isEmpty()
}
