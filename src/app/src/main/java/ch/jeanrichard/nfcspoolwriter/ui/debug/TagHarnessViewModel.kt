package ch.jeanrichard.nfcspoolwriter.ui.debug

import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareClassicSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareSession
import ch.jeanrichard.nfcspoolwriter.data.nfc.MifareTagReaderWriter
import ch.jeanrichard.nfcspoolwriter.data.nfc.OverwriteMode
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagDiagnostics
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagFailure
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagReadResult
import ch.jeanrichard.nfcspoolwriter.data.nfc.TagWriteResult
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Development harness for validating tag behaviour on real hardware.
 *
 * **Development tool, not part of the product flow.** The real write path is the browse → select →
 * confirm → write screens; this exists because validating the *format* means controlling every byte and
 * seeing raw tag contents, which the real flow deliberately does not allow. Reachable from Settings so
 * tag behaviour can be validated on real hardware, which unit tests cannot reach.
 */
class TagHarnessViewModel(
    private val readerWriter: MifareTagReaderWriter,
    private val diagnostics: TagDiagnostics,
    val compatibility: DeviceCompatibility,
) : ViewModel() {

    private val _state = MutableStateFlow(TagHarnessState())
    val state: StateFlow<TagHarnessState> = _state.asStateFlow()

    fun onFormChange(form: HarnessForm) = _state.update { it.copy(form = form) }

    fun onActionChange(action: HarnessAction) = _state.update { it.copy(action = action) }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    /**
     * Called from NFC reader mode on a binder thread when a tag enters the field.
     *
     * Ignores taps while an operation is in flight: a second concurrent session on the same tag
     * would fail confusingly, and during a write it could leave the tag half-written.
     */
    fun onTagDiscovered(tag: Tag) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val session = MifareClassicSession.open(tag)
            if (session == null) {
                // Wrong *tag*, as distinct from wrong *phone* — see DeviceCompatibility.
                append("This tag does not support MIFARE Classic (an NTAG21x or similar).")
                _state.update { it.copy(busy = false) }
                return@launch
            }

            val action = _state.value.action
            val message = when (action) {
                HarnessAction.Diagnose -> diagnostics.report(session)

                HarnessAction.Read -> describe(readerWriter.read(session))

                HarnessAction.Write -> write(session, OverwriteMode.Ask)

                HarnessAction.WriteOverwrite -> write(session, OverwriteMode.Replace)

                HarnessAction.WriteSpoolIdOnly -> write(session, OverwriteMode.SpoolIdOnly)
            }

            append("[${action.label}] $message")
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun write(session: MifareSession, overwrite: OverwriteMode): String =
        when (val fields = _state.value.form.toFields()) {
            is FormResult.Invalid -> {
                session.close()
                "Form invalid: ${fields.reason}"
            }

            is FormResult.Valid -> describe(
                readerWriter.write(session = session, fields = fields.fields, overwrite = overwrite)
            )
        }

    /**
     * Mirrors every result to Logcat as well as the on-screen log. Diagnostic dumps are wider than
     * the screen and the on-screen copy can only be read by scrolling, so `adb logcat -s TagHarness`
     * is the reliable way to capture one in full — which matters when the thing being diagnosed is
     * an unexpected byte layout.
     */
    private fun append(message: String) {
        Log.i(LOG_TAG, message)
        _state.update { it.copy(log = listOf(message) + it.log) }
    }

    private fun describe(result: TagReadResult): String = when (result) {
        TagReadResult.Blank -> "BLANK — sector 1 is on the factory default key."
        is TagReadResult.Written -> buildString {
            appendLine("WRITTEN")
            appendLine("  spool ID  ${result.payload.fields.spoolmanSpoolId}")
            appendLine("  batch     ${result.payload.fields.batchNumber}")
            appendLine("  date      ${result.payload.fields.dateCode}")
            appendLine("  supplier  ${result.payload.fields.supplierId}")
            appendLine("  material  ${result.payload.fields.filamentCatalogId}")
            appendLine("  colour    #${result.payload.fields.colorRgb}")
            appendLine("  weight    ${result.payload.fields.weight.grams} g")
            appendLine("  serial    ${result.payload.serialNumber}")
            append("  reserve   ${result.payload.reserve}")
        }

        is TagReadResult.Corrupt -> "CORRUPT — keyed by us but the payload does not decode: " +
            result.detail

        is TagReadResult.Failed -> "FAILED — ${describe(result.failure)}"
    }

    private fun describe(result: TagWriteResult): String = when (result) {
        TagWriteResult.Success -> "SUCCESS — written and verified by read-back."

        is TagWriteResult.OverwriteRequired ->
            "ALREADY WRITTEN — nothing was written. Existing content:\n" +
                describe(result.existing).prependIndent("  ") +
                "\n\nSwitch to 'Write (overwrite)' to replace it, or 'Write (ID only)' to keep it " +
                "and change just the spool ID, then tap again."

        is TagWriteResult.Failed -> buildString {
            append("FAILED — ${describe(result.failure)}")
            if (result.partiallyWritten) {
                append("\n\nWARNING: the tag may be partially written and inconsistent. ")
                append("Write it again fully before use.")
            }
        }
    }

    private fun describe(failure: TagFailure): String = when (failure) {
        TagFailure.IncompatibleUidLength ->
            "the tag's UID is not 4 bytes, which this format requires (spec §3)."

        TagFailure.UnknownKeyScheme ->
            "sector 1 accepts neither the derived key nor the MIFARE default — this tag uses some " +
                "other key scheme."

        is TagFailure.TagLost ->
            "the tag left the field or I/O failed (${failure.cause?.message}). Try again."

        is TagFailure.VerifyMismatch -> "read-back did not match what was written.\n${failure.detail}"

        is TagFailure.ExistingContentUnreadable ->
            "the tag's existing content does not decode, so there is nothing to preserve around a " +
                "new spool ID.\n${failure.detail}"
    }

    private companion object {
        const val LOG_TAG = "TagHarness"
    }
}

data class TagHarnessState(
    val form: HarnessForm = HarnessForm(),
    val action: HarnessAction = HarnessAction.Diagnose,
    val busy: Boolean = false,
    /** Newest first. */
    val log: List<String> = emptyList(),
)

enum class HarnessAction(val label: String, val writesToTag: Boolean = false) {
    Diagnose("Diagnose"),
    Read("Read"),
    Write("Write", writesToTag = true),
    WriteOverwrite("Write (overwrite)", writesToTag = true),
    WriteSpoolIdOnly("Write (ID only)", writesToTag = true),
}

/**
 * Raw strings rather than typed fields, so invalid input can be typed and reported instead of
 * crashing [MappedFields]'s constructor checks.
 *
 * Defaults are pre-filled for the current printer-acceptance experiment — a black 1 kg eSUN HIPS
 * spool as Spoolman spool 5 — so the test can be run without typing six fields on a phone. Batch,
 * date and supplier keep the community-proven constants, deliberately: the point is to test as few
 * deviations from a known-good tag as possible.
 */
data class HarnessForm(
    val batchNumber: String = MappedFields.DEFAULT_BATCH_NUMBER,
    val dateCode: String = MappedFields.DEFAULT_DATE_CODE,
    val supplierId: String = MappedFields.CREALITY_SUPPLIER_ID,
    /** `00012` = Generic HIPS — an exact match, so no material fallback is involved in this test. */
    val filamentCatalogId: String = "00012",
    val colorRgb: String = "000000",
    val weight: WeightBucket = WeightBucket.G1000,
    val spoolId: String = "5",
) {
    fun toFields(): FormResult {
        val id = spoolId.trim().toIntOrNull()
            ?: return FormResult.Invalid("spool ID '$spoolId' is not a number")
        return try {
            FormResult.Valid(
                MappedFields(
                    batchNumber = batchNumber.trim(),
                    dateCode = dateCode.trim(),
                    supplierId = supplierId.trim(),
                    filamentCatalogId = filamentCatalogId.trim(),
                    colorRgb = colorRgb.trim().removePrefix("#"),
                    weight = weight,
                    spoolmanSpoolId = id,
                )
            )
        } catch (e: IllegalArgumentException) {
            FormResult.Invalid(e.message ?: "invalid field")
        }
    }
}

sealed interface FormResult {
    data class Valid(val fields: MappedFields) : FormResult
    data class Invalid(val reason: String) : FormResult
}
