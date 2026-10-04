package ch.jeanrichard.nfcspoolwriter.ui.materials

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One material, being added or edited.
 *
 * Validation happens on save rather than per keystroke, so a half-typed ID is not shouted at; any
 * edit to a field clears that field's error. The ID of a built-in entry cannot change — the firmware
 * defines what that number means, and renumbering it would be adding a different material.
 *
 * @param materialId null to add a new entry.
 */
class MaterialEditViewModel(
    private val materialId: String?,
    private val materialCatalogRepository: MaterialCatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MaterialEditUiState(isNew = materialId == null))
    val state: StateFlow<MaterialEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val catalog = materialCatalogRepository.catalog.first()
            val entry = materialId?.let(catalog::findById)
            _state.update {
                it.copy(
                    loaded = true,
                    // Asked for an ID that no longer exists: nothing to edit, and adding a new
                    // entry under it would not be what the user tapped on.
                    missing = materialId != null && entry == null,
                    source = entry?.source,
                    id = entry?.id.orEmpty(),
                    name = entry?.name.orEmpty(),
                    brand = entry?.brand.orEmpty(),
                    type = entry?.type.orEmpty(),
                    deprecated = entry?.deprecated ?: false,
                    brandSuggestions = catalog.brands,
                    familySuggestions = catalog.families,
                )
            }
        }
    }

    fun onIdChange(id: String) = _state.update { it.copy(id = id, idError = null) }
    fun onNameChange(name: String) = _state.update { it.copy(name = name, nameError = null) }
    fun onBrandChange(brand: String) = _state.update { it.copy(brand = brand, brandError = null) }
    fun onTypeChange(type: String) = _state.update { it.copy(type = type) }
    fun onDeprecatedChange(deprecated: Boolean) = _state.update { it.copy(deprecated = deprecated) }

    fun save() {
        viewModelScope.launch {
            val catalog = materialCatalogRepository.catalog.first()
            val current = _state.value
            val validated = current.validate(catalog)
            if (validated.hasErrors) {
                _state.update { validated }
                return@launch
            }
            materialCatalogRepository.save(
                entry = current.toEntry(),
                replacingId = materialId?.takeIf { it != current.id.trim() },
            )
            _state.update { it.copy(done = true) }
        }
    }

    fun requestDelete() = _state.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = _state.update { it.copy(confirmingDelete = false) }

    fun confirmDelete() {
        val id = materialId ?: return
        viewModelScope.launch {
            materialCatalogRepository.delete(id)
            _state.update { it.copy(confirmingDelete = false, done = true) }
        }
    }

    fun revert() {
        val id = materialId ?: return
        viewModelScope.launch {
            materialCatalogRepository.revert(id)
            _state.update { it.copy(done = true) }
        }
    }

    private fun MaterialEditUiState.validate(catalog: MaterialCatalog): MaterialEditUiState {
        val trimmedId = id.trim()
        val idError = when {
            !MaterialEntry.isValidId(trimmedId) -> FieldError.IdFormat
            trimmedId != materialId -> catalog.findById(trimmedId)?.let { FieldError.IdTaken(it.name) }
            else -> null
        }
        return copy(
            idError = idError,
            nameError = FieldError.Required.takeIf { name.isBlank() },
            brandError = FieldError.Required.takeIf { brand.isBlank() },
        )
    }

    private fun MaterialEditUiState.toEntry() = MaterialEntry(
        id = id.trim(),
        name = name.trim(),
        brand = brand.trim(),
        type = type.trim().takeIf { it.isNotEmpty() },
        deprecated = deprecated,
    )
}

data class MaterialEditUiState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    /** The entry to edit no longer exists. */
    val missing: Boolean = false,
    /** Null while adding. */
    val source: MaterialSource? = null,
    val id: String = "",
    val name: String = "",
    val brand: String = "",
    /** Blank for no family, which the screen warns about — such an entry never auto-matches. */
    val type: String = "",
    val deprecated: Boolean = false,
    val idError: FieldError? = null,
    val nameError: FieldError? = null,
    val brandError: FieldError? = null,
    val brandSuggestions: List<String> = emptyList(),
    val familySuggestions: List<String> = emptyList(),
    val confirmingDelete: Boolean = false,
    /** Saved, deleted or reverted: the screen should close. */
    val done: Boolean = false,
) {
    val hasErrors: Boolean get() = idError != null || nameError != null || brandError != null

    /** Only an entry the user added can be renumbered; see [MaterialEditViewModel]. */
    val idEditable: Boolean get() = isNew || source == MaterialSource.CUSTOM

    val canDelete: Boolean get() = source == MaterialSource.CUSTOM

    val canRevert: Boolean get() = source == MaterialSource.EDITED
}

/** Named rather than worded: the text lives in `strings.xml`, resolved by the screen. */
sealed interface FieldError {
    data object Required : FieldError
    data object IdFormat : FieldError
    data class IdTaken(val byName: String) : FieldError
}
