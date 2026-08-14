package ch.jeanrichard.nfcspoolwriter.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.data.nfc.DeviceCompatibility
import ch.jeanrichard.nfcspoolwriter.domain.model.WeightBucket
import ch.jeanrichard.nfcspoolwriter.ui.nfc.NfcReaderEffect

/**
 * Temporary development screen for validating tag behaviour on real hardware — see
 * [TagHarnessViewModel] for why it exists.
 */
@Composable
fun TagHarnessScreen(
    viewModel: TagHarnessViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (viewModel.compatibility == DeviceCompatibility.Compatible) {
        NfcReaderEffect(onTag = viewModel::onTagDiscovered)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Tag harness (development)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item { CompatibilityCard(viewModel.compatibility) }

        if (viewModel.compatibility != DeviceCompatibility.Compatible) return@LazyColumn

        item { ActionChips(state.action, viewModel::onActionChange) }

        if (state.action.writesToTag) {
            item { FormFields(state.form, viewModel::onFormChange) }
        }

        item {
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = "Hold the phone against a tag to ${state.action.label.lowercase()}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.log.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Results (newest first)", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = viewModel::clearLog) { Text("Clear") }
                }
            }
        }

        items(state.log) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                // Diagnostic dumps are column-aligned hex, so they must not wrap or re-flow.
                Text(
                    text = entry,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
    }
}

@Composable
private fun CompatibilityCard(compatibility: DeviceCompatibility) {
    val message = when (compatibility) {
        DeviceCompatibility.Compatible ->
            "Device supports MIFARE Classic (com.nxp.mifare present)."

        DeviceCompatibility.NoNfcHardware ->
            "This device has no NFC hardware. The app cannot write tags."

        DeviceCompatibility.NoMifareClassicSupport ->
            "This device has NFC but its chipset cannot do MIFARE Classic. That is a hardware " +
                "limitation with no software workaround — an NXP-based phone is required."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ActionChips(selected: HarnessAction, onSelect: (HarnessAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HarnessAction.entries.forEach { action ->
                FilterChip(
                    selected = action == selected,
                    onClick = { onSelect(action) },
                    label = { Text(action.label) },
                )
            }
        }
        when (selected) {
            HarnessAction.WriteOverwrite -> ActionWarning(
                "Will replace existing tag content without asking."
            )

            HarnessAction.WriteSpoolIdOnly -> ActionWarning(
                "Will keep existing tag content byte for byte and change only the serial-number " +
                    "field, without asking. Fails on a tag whose content does not decode."
            )

            else -> Unit
        }
    }
}

@Composable
private fun ActionWarning(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun FormFields(form: HarnessForm, onChange: (HarnessForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Spool ID", form.spoolId, KeyboardType.Number) { onChange(form.copy(spoolId = it)) }
        Field("Material ID [12,17)", form.filamentCatalogId, KeyboardType.Number) {
            onChange(form.copy(filamentCatalogId = it))
        }
        Field("Colour RRGGBB", form.colorRgb) { onChange(form.copy(colorRgb = it)) }
        Field("Batch number [0,3)", form.batchNumber) { onChange(form.copy(batchNumber = it)) }
        Field("Date code [3,8)", form.dateCode) { onChange(form.copy(dateCode = it)) }
        Field("Supplier ID [8,12)", form.supplierId) { onChange(form.copy(supplierId = it)) }

        Text("Weight", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WeightBucket.entries.forEach { bucket ->
                FilterChip(
                    selected = bucket == form.weight,
                    onClick = { onChange(form.copy(weight = bucket)) },
                    label = { Text("${bucket.grams} g") },
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
