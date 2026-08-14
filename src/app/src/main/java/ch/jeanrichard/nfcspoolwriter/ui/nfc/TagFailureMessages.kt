package ch.jeanrichard.nfcspoolwriter.ui.nfc

import ch.jeanrichard.nfcspoolwriter.data.nfc.TagFailure

/**
 * Not a [TagFailure]: the tag was never opened at all, because it does not implement MIFARE Classic.
 * That is a property of the *tag*, whereas [ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility]
 * is a property of the phone — the two read almost identically to a user, so the wording has to keep
 * them apart.
 */
const val NOT_MIFARE_CLASSIC_MESSAGE =
    "This tag isn't MIFARE Classic, so this app can't work with it. CFS tags are MIFARE Classic 1K."

/**
 * The user-facing text for a [TagFailure], shared by every product screen that touches a tag.
 *
 * Lives here rather than on `TagFailure` itself so the data layer stays free of presentation, and
 * shared rather than copied per screen because the write and read screens describing the same
 * hardware failure differently is how inconsistent error messages start.
 *
 * Each message names the cause **and** the next action, per REQUIREMENTS.md §5 — "the tag moved away"
 * is only useful when followed by "hold the phone still and try again".
 *
 * The development harness (`ui/debug/TagHarnessViewModel`) deliberately keeps its own version: it
 * reports in a diagnostic register, including the underlying exception message and the verify-mismatch
 * detail, which is the opposite of what a user needs here.
 */
fun TagFailure.userMessage(): String = when (this) {
    TagFailure.IncompatibleUidLength ->
        "This tag's serial number is the wrong length for the CFS format. Some MIFARE Classic " +
            "clones use 7-byte UIDs; a compatible tag has a 4-byte UID."

    TagFailure.UnknownKeyScheme ->
        "This tag is locked with keys this app doesn't know, so it isn't blank and wasn't " +
            "written by this app. Use a different tag."

    is TagFailure.TagLost ->
        "The tag moved out of range before the operation finished. Hold the phone still against " +
            "the tag and try again."

    is TagFailure.VerifyMismatch ->
        "The tag was written but reading it back gave something different, so the write can't " +
            "be trusted. Try again."

    is TagFailure.ExistingContentUnreadable ->
        "This tag's existing data could not be read, so its spool ID cannot be changed on its own " +
            "— there is nothing intact left to keep. Tap the tag again and choose to overwrite it " +
            "with the spool's full data."
}
