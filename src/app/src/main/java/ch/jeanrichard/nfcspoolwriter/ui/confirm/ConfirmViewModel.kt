package ch.jeanrichard.nfcspoolwriter.ui.confirm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalogRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingWarning
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * The material is the one value the user can change here, because it is the one where the automatic
 * answer can be wrong without any default being defensible (DESIGN.md DEC-04). A choice made here is
 * for this write only; the write screen re-maps the spool with the same choice.
 *
 * The spool is re-fetched rather than passed between screens, so the values shown are current at the
 * moment of confirmation — Spoolman may have changed since the list was loaded.
 */
class ConfirmViewModel(
    private val spoolId: Int,
    private val spoolmanRepository: SpoolmanRepository,
    private val fieldMappingService: FieldMappingService,
    private val materialCatalogRepository: MaterialCatalogRepository,
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

                is SpoolmanResult.Success -> remap(result.value)
            }
        }
    }

    /**
     * Uses [materialId] for this write instead of the automatic match, or null to go back to it.
     * Re-maps the spool already loaded rather than fetching it again — only the material changed.
     */
    fun chooseMaterial(materialId: String?) {
        val spool = _state.value.spool ?: return
        _state.update { it.copy(chosenMaterialId = materialId) }
        viewModelScope.launch { remap(spool) }
    }

    private suspend fun remap(spool: Spool) {
        val catalog = materialCatalogRepository.catalog.first()
        when (val mapping = fieldMappingService.map(spool, _state.value.chosenMaterialId)) {
            is MappingResult.Mapped -> {
                val entry = mapping.materialMatch.entry
                _state.update {
                    it.copy(
                        loading = false,
                        spool = spool,
                        fields = mapping.fields,
                        materialName = entry?.name,
                        notes = mapping.notes,
                        warnings = mapping.warnings,
                        unmappableReason = null,
                        materialChoices = MaterialChoices.of(catalog, entry?.type),
                    )
                }
            }

            is MappingResult.Unmappable -> _state.update {
                it.copy(
                    loading = false,
                    spool = spool,
                    fields = null,
                    materialName = null,
                    notes = emptyList(),
                    warnings = emptyList(),
                    // Not a crash and not a silent guess: the user is told what can't be mapped so
                    // they can pick a material here or fix it in Spoolman.
                    unmappableReason = mapping.reason,
                    materialChoices = MaterialChoices.of(catalog, spool.filament.material),
                )
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
    /** A material picked by hand for this write, overriding the automatic match. */
    val chosenMaterialId: String? = null,
    /** What the material picker offers; empty until the spool has loaded. */
    val materialChoices: MaterialChoices = MaterialChoices(emptyList(), emptyList()),
) {
    val canWrite: Boolean get() = fields != null && unmappableReason == null
}

/**
 * The catalog arranged for picking a material: entries of the family the spool is (or was matched
 * to) first, since the right answer is almost always a sibling of the automatic one, then everything
 * else in catalog order. Deprecated entries are left out — they must not go onto new tags.
 */
data class MaterialChoices(
    val suggested: List<MaterialEntry>,
    val others: List<MaterialEntry>,
) {
    /** Case-insensitive match on name, brand, family or ID, keeping the two groups apart. */
    fun filter(query: String): MaterialChoices {
        val needle = query.trim()
        if (needle.isEmpty()) return this
        fun MaterialEntry.matches() =
            name.contains(needle, ignoreCase = true) ||
                brand.contains(needle, ignoreCase = true) ||
                (type?.contains(needle, ignoreCase = true) ?: false) ||
                id.contains(needle)
        return MaterialChoices(suggested.filter { it.matches() }, others.filter { it.matches() })
    }

    companion object {
        fun of(catalog: MaterialCatalog, family: String?): MaterialChoices {
            val suggested = family?.takeIf { it.isNotBlank() }?.let(catalog::findAllByType).orEmpty()
            return MaterialChoices(
                suggested = suggested,
                others = catalog.selectable.filterNot { it in suggested },
            )
        }
    }
}
