package ch.jeanrichard.nfcspoolwriter.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The material catalog as a browsable list. Editing happens on [MaterialEditViewModel]; the only
 * change this screen makes itself is the wholesale reset, which is why that one needs confirming.
 */
class MaterialListViewModel(
    private val materialCatalogRepository: MaterialCatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MaterialListUiState())
    val state: StateFlow<MaterialListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            materialCatalogRepository.catalog.collect { catalog ->
                _state.update {
                    it.copy(loaded = true, entries = catalog.all, hasUserChanges = catalog.hasUserChanges)
                }
            }
        }
    }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    fun requestReset() = _state.update { it.copy(confirmingReset = true) }

    fun cancelReset() = _state.update { it.copy(confirmingReset = false) }

    fun confirmReset() {
        _state.update { it.copy(confirmingReset = false) }
        viewModelScope.launch { materialCatalogRepository.resetAll() }
    }
}

data class MaterialListUiState(
    val loaded: Boolean = false,
    val query: String = "",
    val entries: List<MaterialEntry> = emptyList(),
    val hasUserChanges: Boolean = false,
    val confirmingReset: Boolean = false,
) {
    /** Sorted by ID, so the list reads like the firmware's own. */
    val visibleEntries: List<MaterialEntry>
        get() = entries.filter { it.matches(query) }.sortedBy { it.id }

    val isEmptyResult: Boolean get() = loaded && visibleEntries.isEmpty()
}

private fun MaterialEntry.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
        name.contains(needle, ignoreCase = true) ||
        brand.contains(needle, ignoreCase = true) ||
        (type?.contains(needle, ignoreCase = true) ?: false) ||
        id.contains(needle)
}
