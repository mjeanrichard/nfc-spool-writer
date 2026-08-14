package ch.jeanrichard.nfcspoolwriter.ui.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.data.nfc.OverwriteMode
import ch.jeanrichard.nfcspoolwriter.ui.confirm.MessageWithRetry
import ch.jeanrichard.nfcspoolwriter.ui.nfc.NfcReaderEffect

@Composable
fun WriteScreen(
    viewModel: WriteViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reader mode is scoped to this screen, so tags are only handled while the user is here.
    if (viewModel.compatibility == DeviceCompatibility.Compatible && state.canScan) {
        NfcReaderEffect(onTag = viewModel::onTagDiscovered)
    }

    state.overwritePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwrite,
            title = { Text(stringResource(R.string.write_overwrite_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.write_overwrite_body, prompt.existingSummary))
                    if (prompt.canWriteSpoolIdOnly) {
                        Text(
                            stringResource(
                                R.string.write_overwrite_id_only_body,
                                prompt.newSpoolId,
                            )
                        )
                    }
                    Text(
                        text = stringResource(R.string.write_overwrite_hold),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            // Three outcomes do not fit the confirm/dismiss row, so they stack. Keeping the ID-only
            // choice in the dialog is the point of the feature: it is only ever offered here, at the
            // moment the user learns the tag is not empty.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { viewModel.confirmOverwrite(OverwriteMode.Replace) }
                    ) {
                        Text(stringResource(R.string.write_overwrite_confirm))
                    }
                    if (prompt.canWriteSpoolIdOnly) {
                        TextButton(
                            onClick = { viewModel.confirmOverwrite(OverwriteMode.SpoolIdOnly) }
                        ) {
                            Text(stringResource(R.string.write_overwrite_id_only))
                        }
                    }
                    TextButton(onClick = viewModel::cancelOverwrite) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        state.loadError != null -> MessageWithRetry(
            message = state.loadError!!,
            isError = true,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        viewModel.compatibility != DeviceCompatibility.Compatible -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    when (viewModel.compatibility) {
                        DeviceCompatibility.NoNfcHardware -> R.string.nfc_no_hardware
                        else -> R.string.nfc_no_mifare
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.lastWriteVerified) R.string.write_done_heading
                    else R.string.write_tap
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            // Writing a second tag is an option, not a requirement — so explain it only once there
            // is a written tag to repeat, rather than framing the first write as half a job.
            if (state.lastWriteVerified) {
                Text(
                    text = stringResource(R.string.write_multi_tag_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (state.writtenTags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.write_session_heading),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.writtenTags.forEach { uid ->
                    Text(
                        text = stringResource(R.string.write_tag_done, uid),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            state.message?.let { message -> MessageCard(message) }

            if (state.lastWriteVerified) {
                Button(onClick = viewModel::writeAnother, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.write_another))
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_done))
                }
            } else {
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: WriteMessage) {
    val isError = message is WriteMessage.Failed
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (message) {
                is WriteMessage.Written -> Text(
                    text = stringResource(
                        if (message.spoolIdOnly) R.string.write_success_id_only
                        else R.string.write_success
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is WriteMessage.Info -> Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is WriteMessage.Failed -> {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (message.partiallyWritten) {
                        // REQUIREMENTS.md §5: the user must be told the tag may be inconsistent.
                        Text(
                            text = stringResource(R.string.write_partial_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (isError) {
                Text(
                    text = stringResource(R.string.write_retry_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
