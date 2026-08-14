package ch.jeanrichard.nfcspoolwriter.ui.write

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.OverwriteMode
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagFailure
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

    /**
     * How the user approved overwriting each tag, by UID.
     *
     * Keyed by UID rather than held as a single pending decision because approval is about *this*
     * tag: after confirming, the next tap could be a different tag, which has to be asked about on
     * its own terms.
     *
     * An entry outlives the tap it was made for **on purpose** — a confirmed tag that is then never
     * presented keeps its approval for as long as the screen lives, so a user who confirms, gets
     * distracted, and comes back is not asked twice about the same tag. Entries are dropped when the
     * write lands, and when it fails in a way that invalidates the choice itself.
     */
    private val approvedOverwrites = mutableMapOf<String, OverwriteMode>()

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

            val overwrite = approvedOverwrites[uid] ?: OverwriteMode.Ask
            when (val result = tagReaderWriter.write(session, fields, overwrite)) {
                TagWriteResult.Success -> {
                    approvedOverwrites -= uid
                    _state.update { it.copy(writtenTags = it.writtenTags + uid) }
                    finish(
                        WriteMessage.Written(
                            uid = uid,
                            spoolIdOnly = overwrite == OverwriteMode.SpoolIdOnly,
                        )
                    )
                }

                is TagWriteResult.OverwriteRequired -> _state.update {
                    it.copy(
                        busy = false,
                        overwritePrompt = OverwritePrompt(
                            uid = uid,
                            existingSummary = describe(result.existing),
                            existingSpoolId = (result.existing as? TagReadResult.Written)
                                ?.payload?.fields?.spoolmanSpoolId,
                            newSpoolId = fields.spoolmanSpoolId,
                        ),
                    )
                }

                is TagWriteResult.Failed -> {
                    // An ID-only approval was a decision to keep content that has turned out not to
                    // be readable, so it cannot stand: dropping it puts the next tap back at the
                    // prompt, which is where the failure message sends the user. Other failures keep
                    // their approval, since for those tapping again *is* the recovery.
                    if (result.failure is TagFailure.ExistingContentUnreadable) {
                        approvedOverwrites -= uid
                    }
                    finish(
                        WriteMessage.Failed(
                            text = result.failure.userMessage(),
                            retryable = result.failure.retryable,
                            partiallyWritten = result.partiallyWritten,
                        )
                    )
                }
            }
        }
    }

    /**
     * Approves overwriting the prompted tag in [mode], which takes effect on the next tap of it.
     *
     * @param mode never [OverwriteMode.Ask] — the prompt's outcomes are the two ways of saying yes,
     *   and saying no is [cancelOverwrite].
     */
    fun confirmOverwrite(mode: OverwriteMode) {
        val prompt = _state.value.overwritePrompt ?: return
        approvedOverwrites[prompt.uid] = mode
        _state.update {
            it.copy(
                overwritePrompt = null,
                // The tag connection can't be held open across a dialog, so overwriting needs a
                // fresh tap. Say so explicitly rather than leaving the user waiting.
                message = WriteMessage.Info(
                    if (mode == OverwriteMode.SpoolIdOnly) {
                        "Tap the same tag again to change its spool ID."
                    } else {
                        "Tap the same tag again to overwrite it."
                    }
                ),
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

data class OverwritePrompt(
    val uid: String,
    val existingSummary: String,
    /**
     * The spool ID the tag holds now, or null when its content did not parse.
     *
     * Null is exactly the case where [OverwriteMode.SpoolIdOnly] cannot be offered: keeping the rest
     * of a tag whose rest could not be read would leave it as broken as it is, so the only honest
     * choice left is a full overwrite.
     */
    val existingSpoolId: Int?,
    val newSpoolId: Int,
) {
    val canWriteSpoolIdOnly: Boolean get() = existingSpoolId != null
}

sealed interface WriteMessage {
    /** @param spoolIdOnly the tag kept its existing data and only its spool ID changed. */
    data class Written(val uid: String, val spoolIdOnly: Boolean) : WriteMessage

    data class Info(val text: String) : WriteMessage

    /** @param partiallyWritten the tag may be inconsistent and needs rewriting in full. */
    data class Failed(
        val text: String,
        val retryable: Boolean,
        val partiallyWritten: Boolean,
    ) : WriteMessage
}
