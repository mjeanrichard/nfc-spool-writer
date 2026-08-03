package ch.jeanrichard.nfcspoolwriter.ui.read

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.materials.MaterialCatalog
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagReadResult
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.ui.nfc.NOT_MIFARE_CLASSIC_MESSAGE
import ch.jeanrichard.nfcspoolwriter.ui.nfc.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Answers "what is on this tag?" for a tag of unknown provenance (REQUIREMENTS.md §6).
 *
 * **Read-only.** [MifareTagReaderWriter.write] is never called and no key is ever installed, so a tag
 * inspected here comes away byte-identical. That is the whole contract of the screen: a user pointing
 * an unknown tag at the phone must not risk changing it.
 *
 * Distinct from the debug harness, which reports the same read in a diagnostic register — trailer
 * bits, raw hex, the reserve field. This one shows only what a user can act on.
 *
 * @param openSession injected so the logic is testable without a real `android.nfc.Tag`, which cannot
 *   be constructed in a unit test.
 */
class ReadTagViewModel(
    private val tagReaderWriter: MifareTagReaderWriter,
    private val materialCatalog: MaterialCatalog,
    private val spoolmanRepository: SpoolmanRepository,
    val compatibility: DeviceCompatibility,
    private val openSession: (Tag) -> MifareSession?,
) : ViewModel() {

    private val _state = MutableStateFlow(ReadUiState())
    val state: StateFlow<ReadUiState> = _state.asStateFlow()

    /** The in-flight Spoolman lookup, so a slow one for an earlier tag cannot land on a later one. */
    private var lookupJob: Job? = null

    /** Called from NFC reader mode on a binder thread. */
    fun onTagDiscovered(tag: Tag) {
        // A second concurrent session on the same tag fails confusingly, so a tap mid-read is dropped.
        if (_state.value.busy) return

        lookupJob?.cancel()
        _state.update { it.copy(busy = true, outcome = null, lookup = null) }

        viewModelScope.launch {
            val session = openSession(tag)
            if (session == null) {
                // Wrong *tag*, distinct from wrong *phone* — see DeviceCompatibility.
                finish(ReadOutcome.Failed(NOT_MIFARE_CLASSIC_MESSAGE, retryable = false))
                return@launch
            }

            when (val result = tagReaderWriter.read(session)) {
                TagReadResult.Blank -> finish(ReadOutcome.Blank)

                is TagReadResult.Written -> {
                    finish(ReadOutcome.Written(summarize(result.payload.fields)))
                    lookUpSpool(result.payload.fields.spoolmanSpoolId)
                }

                is TagReadResult.Corrupt -> finish(ReadOutcome.Corrupt)

                is TagReadResult.Failed -> finish(
                    ReadOutcome.Failed(
                        text = result.failure.userMessage(),
                        retryable = result.failure.retryable,
                    )
                )
            }
        }
    }

    /**
     * Puts a name to the spool ID the tag carries, turning "spool 42" into "eSUN Black PLA".
     *
     * Deliberately a separate job started *after* [busy] clears: the tag is already read and shown, so
     * a slow or unreachable server must delay neither the result nor the next tap. Every failure is
     * reported as an unavailable line beside the tag's own fields, which stand on their own.
     */
    private fun lookUpSpool(spoolId: Int) {
        _state.update { it.copy(lookup = SpoolLookup.Loading) }
        lookupJob = viewModelScope.launch {
            val lookup = when (val result = spoolmanRepository.getSpool(spoolId)) {
                is SpoolmanResult.Success -> SpoolLookup.Found(result.value)
                is SpoolmanResult.Failure -> SpoolLookup.Unavailable(result.error.userMessage)
            }
            _state.update { it.copy(lookup = lookup) }
        }
    }

    private fun summarize(fields: MappedFields) = TagSummary(
        materialName = materialCatalog.findById(fields.filamentCatalogId)?.name,
        materialId = fields.filamentCatalogId,
        colorRgb = fields.normalizedColorRgb,
        weightGrams = fields.weight.grams,
        spoolId = fields.spoolmanSpoolId,
        batchNumber = fields.batchNumber,
        dateCode = fields.dateCode,
        supplierId = fields.supplierId,
    )

    private fun finish(outcome: ReadOutcome) =
        _state.update { it.copy(busy = false, outcome = outcome) }
}

data class ReadUiState(
    /** A read is in flight; further taps are ignored. */
    val busy: Boolean = false,
    val outcome: ReadOutcome? = null,
    /** Only ever set for a [ReadOutcome.Written] tag, which is the only one carrying a spool ID. */
    val lookup: SpoolLookup? = null,
) {
    /**
     * Reader mode stays bound even after a result, unlike the write screen — which unbinds so a stray
     * tap cannot write an unrelated tag. Reading carries no such hazard, and working through a handful
     * of unknown tags is exactly what this screen is for, so each tap simply replaces the last result.
     */
    val canScan: Boolean get() = !busy
}

sealed interface ReadOutcome {

    /** Still on the factory key: nothing has been written to it in this format. */
    data object Blank : ReadOutcome

    data class Written(val tag: TagSummary) : ReadOutcome

    /**
     * Keyed by this format but the payload does not decode — almost always an interrupted write.
     *
     * Carries no detail on purpose: `TagReadResult.Corrupt.detail` is a codec diagnostic, and the
     * remedy ("write it again") is the same whatever it says. The harness is where the detail belongs.
     */
    data object Corrupt : ReadOutcome

    data class Failed(val text: String, val retryable: Boolean) : ReadOutcome
}

/**
 * A decoded tag in the terms the confirm screen uses, so the same values read the same way on both
 * sides of a write (REQUIREMENTS.md §6).
 *
 * The reserve field is left out: it is derived from the spool ID on write and means nothing to a user.
 */
data class TagSummary(
    /** Catalog name for [materialId], or null when the tag names an ID this app's catalog lacks. */
    val materialName: String?,
    val materialId: String,
    /** 6 hex digits, upper case, no `#`. */
    val colorRgb: String,
    val weightGrams: Int,
    val spoolId: Int,
    val batchNumber: String,
    val dateCode: String,
    val supplierId: String,
)

/** What Spoolman knows about the spool ID found on the tag. */
sealed interface SpoolLookup {
    data object Loading : SpoolLookup
    data class Found(val spool: Spool) : SpoolLookup

    /** No server configured, unreachable, or the spool has since been deleted. */
    data class Unavailable(val text: String) : SpoolLookup
}
