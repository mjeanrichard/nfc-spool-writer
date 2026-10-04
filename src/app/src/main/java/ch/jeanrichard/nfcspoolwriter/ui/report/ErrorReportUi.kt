package ch.jeanrichard.nfcspoolwriter.ui.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.appContainer
import ch.jeanrichard.nfcspoolwriter.data.report.EmailDraft
import ch.jeanrichard.nfcspoolwriter.data.report.ErrorReport
import kotlinx.coroutines.launch

/**
 * @return a function that opens [ErrorReport]s as an email draft in the user's own mail app. Nothing
 *   is sent by this app: the user reads the draft, can edit it, and decides whether to send it.
 */
@Composable
fun rememberErrorReportSender(): (ErrorReport) -> Unit {
    val context = LocalContext.current
    val composer = context.appContainer.errorReportComposer
    val scope = rememberCoroutineScope()
    return remember(context, composer, scope) {
        { report -> scope.launch { context.openEmailDraft(composer.compose(report)) } }
    }
}

/**
 * Prefers a mail app, so the recipient and subject are filled in. A phone without one still gets
 * the share sheet, where the same text can go to whatever the user does have.
 */
private fun Context.openEmailDraft(draft: EmailDraft) {
    fun Intent.withDraft() = apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(draft.to))
        putExtra(Intent.EXTRA_SUBJECT, draft.subject)
        putExtra(Intent.EXTRA_TEXT, draft.body)
    }
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).withDraft())
    } catch (_: ActivityNotFoundException) {
        startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").withDraft(), null)
        )
    }
}

/** Asks, once, whether to report the crash that ended the previous run. */
@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val send = rememberErrorReportSender()
    val report = pending ?: return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crash_report_body))
                Text(
                    text = stringResource(R.string.report_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    send(report)
                    viewModel.dismiss()
                }
            ) { Text(stringResource(R.string.action_send_report)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}

/** The way out of a failure message for a user who thinks it should not have happened. */
@Composable
fun ReportProblemButton(report: ErrorReport, modifier: Modifier = Modifier) {
    val send = rememberErrorReportSender()
    TextButton(onClick = { send(report) }, modifier = modifier) {
        Text(stringResource(R.string.action_report_problem))
    }
}
