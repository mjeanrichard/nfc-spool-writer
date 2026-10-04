package ch.jeanrichard.nfcspoolwriter.data.report

/** What the report says about the app and phone — enough to reproduce, nothing about the user. */
data class ReportEnvironment(
    val appVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
)

data class EmailDraft(val to: String, val subject: String, val body: String)

/**
 * Turns an [ErrorReport] into the email the user is shown before anything is sent.
 *
 * Redaction happens here rather than when a report is recorded because this is the single point
 * where text leaves the app, and because only here is the configured server address at hand — a
 * crash handler cannot wait on the settings store.
 *
 * @param serverAddresses the addresses to strip by name; see [ReportRedactor.redact].
 */
class ErrorReportComposer(
    private val environment: ReportEnvironment,
    private val serverAddresses: suspend () -> List<String>,
) {

    suspend fun compose(report: ErrorReport): EmailDraft {
        val details = ReportRedactor.redact(report.details, serverAddresses()).let {
            if (it.length <= MAX_DETAILS_LENGTH) it else it.take(MAX_DETAILS_LENGTH) + TRUNCATED
        }
        val body = buildString {
            append("What were you doing when this happened? (optional)\n\n\n")
            append("---\n")
            append("Report: ").append(report.kind.label).append('\n')
            append("App: ").append(environment.appVersion).append('\n')
            append("Android: ").append(environment.androidRelease)
                .append(" (API ").append(environment.sdkInt).append(")\n")
            append("Device: ").append(environment.device).append("\n\n")
            append(details)
        }
        return EmailDraft(
            to = RECIPIENT,
            subject = "NFC Spool Writer ${report.kind.label} report (${environment.appVersion})",
            body = body,
        )
    }

    companion object {
        const val RECIPIENT = "apps@jean-richard.ch"

        /**
         * Well past any real stack trace, and well short of what an intent extra can carry — a
         * chain of deeply nested causes must not make the email app refuse the draft.
         */
        const val MAX_DETAILS_LENGTH = 16_000
        const val TRUNCATED = "\n… (truncated)"
    }
}
