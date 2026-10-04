package ch.jeanrichard.nfcspoolwriter.data.report

import ch.jeanrichard.nfcspoolwriter.data.nfc.TagFailure

/**
 * Something the user may choose to email to the developer. Never sent by the app itself: it only
 * becomes an email draft the user reads, edits and sends from their own mail app.
 *
 * [details] is raw diagnostic text and may still contain addresses or tag IDs; [ReportRedactor]
 * cleans it when the draft is composed, which is the one point it leaves the app.
 */
data class ErrorReport(val kind: Kind, val details: String) {

    enum class Kind(val label: String) {
        Crash("crash"),
        TagFailure("tag error"),
    }

    companion object {

        /**
         * A report for a tag operation that failed without crashing.
         *
         * Carries the failure type and the underlying exception, but not [TagFailure.VerifyMismatch]
         * or [TagFailure.ExistingContentUnreadable] details: those hold the tag's content, which is
         * spool data, and the failure type alone says which branch went wrong.
         *
         * @param context facts about the attempt — overwrite mode, whether blocks were written —
         *   listed in the order given.
         */
        fun tagFailure(
            operation: String,
            failure: TagFailure,
            context: List<Pair<String, Any>> = emptyList(),
        ): ErrorReport {
            val facts = listOf("Operation" to operation, "Failure" to failure.typeName()) + context
            val details = buildString {
                facts.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
                if (failure is TagFailure.TagLost && failure.cause != null) {
                    append('\n').append(failure.cause.stackTraceToString())
                }
            }
            return ErrorReport(Kind.TagFailure, details.trimEnd())
        }

        /** Spelled out rather than read from the class name, which R8 renames once enabled. */
        private fun TagFailure.typeName(): String = when (this) {
            TagFailure.IncompatibleUidLength -> "IncompatibleUidLength"
            TagFailure.UnknownKeyScheme -> "UnknownKeyScheme"
            is TagFailure.TagLost -> "TagLost"
            is TagFailure.VerifyMismatch -> "VerifyMismatch"
            is TagFailure.ExistingContentUnreadable -> "ExistingContentUnreadable"
        }
    }
}
