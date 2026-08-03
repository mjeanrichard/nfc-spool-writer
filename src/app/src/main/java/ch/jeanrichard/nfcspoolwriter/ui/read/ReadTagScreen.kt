package ch.jeanrichard.nfcspoolwriter.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool
import ch.jeanrichard.nfcspoolwriter.ui.confirm.FieldRow
import ch.jeanrichard.nfcspoolwriter.ui.nfc.NfcReaderEffect

/**
 * Reports what is on a tag the user presents, and nothing else — no write, no key installation
 * (REQUIREMENTS.md §6). Reachable from the spool list without selecting a spool, since the question
 * it answers is about the tag, not about a spool.
 */
@Composable
fun ReadTagScreen(
    viewModel: ReadTagViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reader mode is scoped to this screen, so tags are only handled while the user is here.
    if (viewModel.compatibility == DeviceCompatibility.Compatible && state.canScan) {
        NfcReaderEffect(onTag = viewModel::onTagDiscovered)
    }

    if (viewModel.compatibility != DeviceCompatibility.Compatible) {
        Box(
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
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (state.outcome == null) R.string.read_tap else R.string.read_another
            ),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        when (val outcome = state.outcome) {
            null -> Unit
            ReadOutcome.Blank -> VerdictCard(
                title = stringResource(R.string.read_blank_title),
                body = stringResource(R.string.read_blank_body),
            )

            ReadOutcome.Corrupt -> VerdictCard(
                title = stringResource(R.string.read_corrupt_title),
                body = stringResource(R.string.read_corrupt_body),
                isError = true,
            )

            is ReadOutcome.Failed -> VerdictCard(
                title = stringResource(R.string.read_failed_title),
                body = outcome.text,
                isError = true,
                hint = if (outcome.retryable) stringResource(R.string.write_retry_hint) else null,
            )

            is ReadOutcome.Written -> {
                VerdictCard(
                    title = stringResource(R.string.read_written_title),
                    body = stringResource(R.string.read_written_body),
                )
                TagFields(outcome.tag)
                state.lookup?.let { SpoolmanCard(it) }
            }
        }
    }
}

@Composable
private fun VerdictCard(
    title: String,
    body: String,
    isError: Boolean = false,
    hint: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TagFields(tag: TagSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.read_fields_title),
                style = MaterialTheme.typography.titleSmall,
            )
            FieldRow(
                stringResource(R.string.field_material),
                tag.materialName?.let { "$it (${tag.materialId})" }
                    ?: stringResource(R.string.read_material_unknown, tag.materialId),
            )
            ColourRow(tag.colorRgb)
            FieldRow(stringResource(R.string.field_weight), "${tag.weightGrams} g")
            FieldRow(stringResource(R.string.field_serial), tag.spoolId.toString())
            FieldRow(stringResource(R.string.field_supplier), tag.supplierId)
            FieldRow(stringResource(R.string.field_batch), tag.batchNumber)
            FieldRow(stringResource(R.string.field_date), tag.dateCode)
        }
    }
}

/**
 * The colour row carries a chip as well as the hex, because a hex triplet is not something a user can
 * compare against the spool in their hand.
 */
@Composable
private fun ColourRow(colorRgb: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.field_colour),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    Color(colorRgb.toInt(16) or OPAQUE),
                    RoundedCornerShape(4.dp),
                )
                // Outlined so a white or near-black chip does not vanish into the card.
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                // The hex beside it already says everything; the chip is decoration for the eye.
                .clearAndSetSemantics { },
        )
        Text(text = "#$colorRgb", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpoolmanCard(lookup: SpoolLookup) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.read_spoolman_title),
                style = MaterialTheme.typography.titleSmall,
            )
            when (lookup) {
                SpoolLookup.Loading -> Text(
                    text = stringResource(R.string.read_spoolman_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is SpoolLookup.Found -> {
                    Text(
                        text = spoolLabel(lookup.spool),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    lookup.spool.location?.let { location ->
                        Text(
                            text = stringResource(R.string.read_spoolman_location, location),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is SpoolLookup.Unavailable -> Text(
                    text = lookup.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun spoolLabel(spool: Spool): String {
    val name = spool.filament.name
        ?: spool.filament.material
        ?: stringResource(R.string.spools_unnamed)
    return spool.filament.vendor?.name?.let { "$name ($it)" } ?: name
}

private const val OPAQUE = 0xFF000000.toInt()
