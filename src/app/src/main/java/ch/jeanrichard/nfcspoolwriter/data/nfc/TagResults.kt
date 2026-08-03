package ch.jeanrichard.nfcspoolwriter.data.nfc

import ch.jeanrichard.nfcspoolwriter.domain.tagcodec.DecodedPayload

/**
 * Why a tag operation failed, and whether another tap could plausibly succeed.
 *
 * Typed rather than thrown so the UI can render retry/error states without ad-hoc exception
 * handling across the NFC/Compose boundary (DESIGN.md §1.2).
 */
sealed interface TagFailure {

    /** True when the same tag and phone might still succeed — i.e. worth asking for another tap. */
    val retryable: Boolean

    /** 7-byte "double size" UID, or anything else that isn't 4 bytes (spec §3). */
    data object IncompatibleUidLength : TagFailure {
        override val retryable: Boolean get() = false
    }

    /** Neither the derived key nor the MIFARE default authenticated — some unrelated key scheme. */
    data object UnknownKeyScheme : TagFailure {
        override val retryable: Boolean get() = false
    }

    /** Tag moved out of range, or I/O failed after exhausting in-session retries. */
    data class TagLost(val cause: Throwable?) : TagFailure {
        override val retryable: Boolean get() = true
    }

    /** The write reported success but reading it back produced something else. */
    data class VerifyMismatch(val detail: String) : TagFailure {
        override val retryable: Boolean get() = true
    }
}

sealed interface TagReadResult {

    /** Sector 1 is still on the factory default key — nothing this format wrote is present. */
    data object Blank : TagReadResult

    /** Secured by this format, and the payload parsed cleanly. */
    data class Written(val payload: DecodedPayload) : TagReadResult

    /**
     * Secured by this format, but the content does not parse. Most likely an interrupted earlier
     * write. Kept distinct from [Blank] because it is *not* safe to treat as an empty tag: the
     * sector key has already been changed, so the write path must take the already-secured branch.
     */
    data class Corrupt(val detail: String) : TagReadResult

    data class Failed(val failure: TagFailure) : TagReadResult
}

sealed interface TagWriteResult {

    data object Success : TagWriteResult

    /**
     * The tag already holds data and [MifareTagReaderWriter.write] was not permitted to overwrite.
     * Nothing has been written. After the user confirms, call `write` again with
     * `allowOverwrite = true` — which needs a fresh tap, since the tag connection cannot be held
     * open across a confirmation dialog.
     */
    data class OverwriteRequired(val existing: TagReadResult) : TagWriteResult

    /**
     * @param partiallyWritten true if at least one block was written before the failure, meaning the
     *   tag may be in an inconsistent state and should be rewritten fully before use
     *   (REQUIREMENTS.md §5).
     */
    data class Failed(
        val failure: TagFailure,
        val partiallyWritten: Boolean,
    ) : TagWriteResult
}
