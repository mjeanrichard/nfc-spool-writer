package ch.jeanrichard.nfcspoolwriter.ui.confirm

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.domain.mapping.MappingWarning
import ch.jeanrichard.nfcspoolwriter.domain.model.MappedFields

/**
 * The last chance to catch a bad auto-mapping before it is burned onto a tag. Shows both the values
 * that will be written and every approximation the mapping made to get them.
 */
@Composable
fun ConfirmScreen(
    viewModel: ConfirmViewModel,
    onWrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        state.error != null -> MessageWithRetry(
            message = state.error!!,
            isError = true,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.spool?.let { spool ->
                Text(
                    text = spool.filament.name
                        ?: spool.filament.material
                        ?: stringResource(R.string.spools_unnamed),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.spools_id, spool.id),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.unmappableReason?.let { reason ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.confirm_unmappable_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.confirm_unmappable_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Above the field table rather than beside the notes: a warning is about a value that
            // looks perfectly correct in the table, so it has to be read before the table is. Only the
            // title is tinted, matching the unmappable card — a filled card would read as the stronger
            // of the two, and this one does not block the write.
            if (state.warnings.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.confirm_warnings_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        state.warnings.forEach { warning ->
                            Text(
                                text = stringResource(warning.messageRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            state.fields?.let { fields ->
                FieldTable(fields = fields, materialName = state.materialName)
            }

            if (state.notes.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.confirm_notes_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        state.notes.forEach { note ->
                            Text(text = "• $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Button(
                onClick = onWrite,
                enabled = state.canWrite,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.confirm_write)) }
        }
    }
}

/**
 * The user-facing text for a warning the domain layer only names. Kept as an exhaustive `when` so a new
 * [MappingWarning] cannot reach the screen without one.
 */
private val MappingWarning.messageRes: Int
    @StringRes get() = when (this) {
        MappingWarning.IGNORED_SPOOL_ID -> R.string.confirm_warning_ignored_spool_id
    }

@Composable
private fun FieldTable(fields: MappedFields, materialName: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.confirm_fields_title),
                style = MaterialTheme.typography.titleSmall,
            )
            FieldRow(
                stringResource(R.string.field_material),
                listOfNotNull(materialName, "(${fields.filamentCatalogId})").joinToString(" "),
            )
            FieldRow(stringResource(R.string.field_colour), "#${fields.colorRgb}")
            FieldRow(stringResource(R.string.field_weight), "${fields.weight.grams} g")
            FieldRow(stringResource(R.string.field_serial), fields.spoolmanSpoolId.toString())
            FieldRow(stringResource(R.string.field_supplier), fields.supplierId)
        }
    }
}

/** Shared with the read screen, so a field reads identically before and after a write. */
@Composable
internal fun FieldRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun MessageWithRetry(
    message: String,
    isError: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
