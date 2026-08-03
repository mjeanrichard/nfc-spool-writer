package ch.jeanrichard.nfcspoolwriter.ui.write

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagReadResult
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagWriteResult
import ch.jeanrichard.nfcspoolwriter.data.nfc.toHexDump
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanRepository
import ch.jeanrichard.nfcspoolwriter.data.spoolman.SpoolmanResult
import ch.jeanrichard.nfcspoolwriter.domain.mapping.FieldMappingService
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingResult
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.ui.nfc.NOT_MIFARE_CLASSIC_MESSAGE
import ch.jeanrichard.nfcspoolwriter.ui.nfc.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes a spool's data to one tag at a time.
 *
 * **One tag per write** (REQUIREMENTS.md §5). A write is complete once a single tag is written and
 * verified. Genuine Creality spools happen to carry two tags with identical payloads, but a spool the
 * user tags themselves may carry one, two or none — so how many tags a spool gets is the user's
 * choice, not progress the app tracks. After a verified write the screen offers [writeAnother], which
 * re-arms for a fresh tap with the *same* [MappedFields]: no reload, no re-confirm.
 *
 * [WriteUiState.writtenTags] is therefore not a progress counter. It exists so a tag tapped twice in
 * one session is recognised by UID and reported, rather than silently rewritten.
 *
 * @param openSession injected so the write logic is testable without a real `android.nfc.Tag`, which
 *   cannot be constructed in a unit test.
 */
class WriteViewModel(
    private val spoolId: Int,
    private val spoolmanRepository: SpoolmanRepository,
    private val fieldMappingService: FieldMappingService,
    private val tagReaderWriter: MifareTagReaderWriter,
    val compatibility: DeviceCompatibility,
    private val openSession: (Tag) -> MifareSession?,
) : ViewModel() {

    private val _state = MutableStateFlow(WriteUiState())
    val state: StateFlow<WriteUiState> = _state.asStateFlow()

    /** UIDs the user has explicitly approved overwriting. Cleared once written. */
    private val approvedOverwrites = mutableSetOf<String>()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = spoolmanRepository.getSpool(spoolId)) {
                is SpoolmanResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.error.userMessage)
                }

                is SpoolmanResult.Success -> when (
                    val mapping = fieldMappingService.map(result.value)
                ) {
                    is MappingResult.Mapped -> _state.update {
                        it.copy(loading = false, fields = mapping.fields)
                    }

                    is MappingResult.Unmappable -> _state.update {
                        it.copy(loading = false, loadError = mapping.reason)
                    }
                }
            }
        }
    }

    /** Called from NFC reader mode on a binder thread. */
    fun onTagDiscovered(tag: Tag) {
        val current = _state.value
        val fields = current.fields ?: return
        // Ignore taps while a write is in flight or a decision is pending: a second session on the
        // same tag would fail confusingly, and mid-write it could leave the tag half-written.
        if (current.busy || current.overwritePrompt != null) return

        _state.update { it.copy(busy = true, message = null) }

        viewModelScope.launch {
            val session = openSession(tag)
            if (session == null) {
                // Wrong *tag*, distinct from wrong *phone* — see DeviceCompatibility.
                finish(WriteMessage.Info(NOT_MIFARE_CLASSIC_MESSAGE))
                return@launch
            }

            val uid = session.uid.toHexDump()
            if (uid in _state.value.writtenTags) {
                session.close()
                finish(
                    WriteMessage.Info(
                        "This tag was already written in this session. Present a different tag, or " +
                            "tap Done if you are finished."
                    )
                )
                return@launch
            }

            val allowOverwrite = uid in approvedOverwrites
            when (val result = tagReaderWriter.write(session, fields, allowOverwrite)) {
                TagWriteResult.Success -> {
                    approvedOverwrites -= uid
                    _state.update { it.copy(writtenTags = it.writtenTags + uid) }
                    finish(WriteMessage.Written(uid))
                }

                is TagWriteResult.OverwriteRequired -> _state.update {
                    it.copy(
                        busy = false,
                        overwritePrompt = OverwritePrompt(uid, describe(result.existing)),
                    )
                }

                is TagWriteResult.Failed -> finish(
                    WriteMessage.Failed(
                        text = result.failure.userMessage(),
                        retryable = result.failure.retryable,
                        partiallyWritten = result.partiallyWritten,
                    )
                )
            }
        }
    }

    fun confirmOverwrite() {
        val prompt = _state.value.overwritePrompt ?: return
        approvedOverwrites += prompt.uid
        _state.update {
            it.copy(
                overwritePrompt = null,
                // The tag connection can't be held open across a dialog, so overwriting needs a
                // fresh tap. Say so explicitly rather than leaving the user waiting.
                message = WriteMessage.Info("Tap the same tag again to overwrite it."),
            )
        }
    }

    fun cancelOverwrite() = _state.update { it.copy(overwritePrompt = null, message = null) }

    /**
     * Re-arms for another tag with the data already loaded — clearing the success message is what
     * re-enables scanning (see [WriteUiState.canScan]). Deliberately does not touch [WriteUiState.fields]
     * or reload the spool: the next tag must get byte-identical data, and a reload could silently pick
     * up an edit made in Spoolman since the user confirmed.
     */
    fun writeAnother() = _state.update { it.copy(message = null) }

    private fun finish(message: WriteMessage) =
        _state.update { it.copy(busy = false, message = message) }

    private fun describe(existing: TagReadResult): String = when (existing) {
        is TagReadResult.Written -> with(existing.payload.fields) {
            "Spool $spoolmanSpoolId, material $filamentCatalogId, #$colorRgb, ${weight.grams} g"
        }

        is TagReadResult.Corrupt -> "unreadable data from an earlier interrupted write"
        TagReadResult.Blank -> "no data"
        is TagReadResult.Failed -> "data that could not be read"
    }
}

data class WriteUiState(
    val loading: Boolean = false,
    val fields: MappedFields? = null,
    val loadError: String? = null,
    /** A write is in flight; further taps are ignored. */
    val busy: Boolean = false,
    /**
     * UIDs written and verified in this session, in order.
     *
     * Not a progress counter — nothing is "complete" at any particular size. It only lets a re-tap of
     * a tag already done be recognised and reported instead of silently rewritten.
     */
    val writtenTags: List<String> = emptyList(),
    val message: WriteMessage? = null,
    val overwritePrompt: OverwritePrompt? = null,
) {
    /** A tag was written and verified; the screen is now waiting for "write another" or "done". */
    val lastWriteVerified: Boolean get() = message is WriteMessage.Written

    /**
     * Reader mode stays bound while this holds. It goes false after a verified write so the terminal
     * success state is genuinely terminal: a stray tap must not start writing an unrelated tag the
     * user only meant to move out of the way. A *failure* message leaves scanning armed, since
     * tapping again is exactly the recovery.
     */
    val canScan: Boolean get() = fields != null && loadError == null && !lastWriteVerified
}

data class OverwritePrompt(val uid: String, val existingSummary: String)

sealed interface WriteMessage {
    data class Written(val uid: String) : WriteMessage

    data class Info(val text: String) : WriteMessage

    /** @param partiallyWritten the tag may be inconsistent and needs rewriting in full. */
    data class Failed(
        val text: String,
        val retryable: Boolean,
        val partiallyWritten: Boolean,
    ) : WriteMessage
}
