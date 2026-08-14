package ch.jeanrichard.nfcspoolwriter.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingWarning
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shows exactly what will be written before anything is committed to a tag.
 *
 * This screen exists because the mapping is best-effort (REQUIREMENTS.md §4,
 * "confirm-before-write"): weight gets rounded to a bucket, materials fall back to a near relative, missing colours get
 * a placeholder. Burning a bad auto-mapping onto a tag and finding out at the printer is the failure
 * this prevents, so every approximation is listed rather than merely applied.
 *
 * The spool is re-fetched rather than passed between screens, so the values shown are current at the
 * moment of confirmation — Spoolman may have changed since the list was loaded.
 */
class ConfirmViewModel(
    private val spoolId: Int,
    private val spoolmanRepository: SpoolmanRepository,
    private val fieldMappingService: FieldMappingService,
    private val materialCatalog: MaterialCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow(ConfirmUiState())
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = spoolmanRepository.getSpool(spoolId)) {
                is SpoolmanResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.userMessage)
                }

                is SpoolmanResult.Success -> {
                    val spool = result.value
                    when (val mapping = fieldMappingService.map(spool)) {
                        is MappingResult.Mapped -> _state.update {
                            it.copy(
                                loading = false,
                                spool = spool,
                                fields = mapping.fields,
                                materialName = materialCatalog
                                    .findById(mapping.fields.filamentCatalogId)?.name,
                                notes = mapping.notes,
                                warnings = mapping.warnings,
                            )
                        }

                        is MappingResult.Unmappable -> _state.update {
                            it.copy(
                                loading = false,
                                spool = spool,
                                // Not a crash and not a silent guess: the user is told what can't be
                                // mapped so they can fix the material in Spoolman.
                                unmappableReason = mapping.reason,
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ConfirmUiState(
    val loading: Boolean = false,
    val spool: Spool? = null,
    val fields: MappedFields? = null,
    /** Human-readable catalog name for the chosen material ID, e.g. `Generic HIPS`. */
    val materialName: String? = null,
    /** Approximations the mapping made, shown so the user can catch a bad auto-mapping. */
    val notes: List<String> = emptyList(),
    /** Values written as-is that will still misbehave at the printer. Does not block writing. */
    val warnings: List<MappingWarning> = emptyList(),
    val unmappableReason: String? = null,
    val error: String? = null,
) {
    val canWrite: Boolean get() = fields != null && unmappableReason == null
}
