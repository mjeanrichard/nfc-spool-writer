package ch.jeanrichard.nfcspoolwriter.ui.materials

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R

/**
 * Adds or edits one material. Closes itself through [onDone] once the change is stored.
 */
@Composable
fun MaterialEditScreen(
    viewModel: MaterialEditViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    when {
        !state.loaded -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        state.missing -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.material_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        else -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.idEditable) {
                Text(
                    text = stringResource(R.string.material_builtin_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = state.id,
                onValueChange = viewModel::onIdChange,
                label = { Text(stringResource(R.string.material_id_label)) },
                supportingText = {
                    Text(
                        when (val error = state.idError) {
                            null -> stringResource(R.string.material_id_hint)
                            FieldError.IdFormat -> stringResource(R.string.material_id_error_format)
                            is FieldError.IdTaken ->
                                stringResource(R.string.material_id_error_taken, error.byName)
                            FieldError.Required -> stringResource(R.string.material_required)
                        }
                    )
                },
                isError = state.idError != null,
                enabled = state.idEditable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.material_name_label)) },
                supportingText = state.nameError?.let {
                    { Text(stringResource(R.string.material_required)) }
                },
                isError = state.nameError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SuggestingTextField(
                value = state.brand,
                onValueChange = viewModel::onBrandChange,
                suggestions = state.brandSuggestions,
                label = stringResource(R.string.material_brand_label),
                supportingText = state.brandError?.let { stringResource(R.string.material_required) },
                isError = state.brandError != null,
            )

            SuggestingTextField(
                value = state.type,
                onValueChange = viewModel::onTypeChange,
                suggestions = state.familySuggestions,
                label = stringResource(R.string.material_family_label),
                supportingText = stringResource(R.string.material_family_hint),
                isError = false,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.material_deprecated_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.material_deprecated_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.deprecated, onCheckedChange = viewModel::onDeprecatedChange)
            }

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.material_save))
            }

            if (state.canRevert) {
                OutlinedButton(onClick = viewModel::revert, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.material_revert))
                }
            }

            if (state.canDelete) {
                OutlinedButton(
                    onClick = viewModel::requestDelete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.material_delete)) }
            }
        }
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.material_delete_title)) },
            text = { Text(stringResource(R.string.material_delete_body, state.id)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.material_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * A free-text field that offers the values already in the catalog as it is typed into. Suggestions
 * rather than a fixed list: a new brand or family is exactly what adding a material may need, and
 * yet a typo in a family name (`PETg`) would silently break matching, so existing spellings are
 * one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
    supportingText: String?,
    isError: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val matching = suggestions.filter { it.contains(value, ignoreCase = true) && it != value }

    ExposedDropdownMenuBox(
        expanded = expanded && matching.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            supportingText = supportingText?.let { { Text(it) } },
            isError = isError,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && matching.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            matching.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        onValueChange(suggestion)
                        expanded = false
                    },
                )
            }
        }
    }
}
