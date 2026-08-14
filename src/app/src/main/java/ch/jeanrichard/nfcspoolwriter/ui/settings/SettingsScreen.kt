package ch.jeanrichard.nfcspoolwriter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R

/**
 * @param onOpenHarness null in release builds, where the development harness is not reachable at
 *   all. Passing the absence down as a null callback keeps the build-variant decision in one place
 *   (AppNavigation) instead of spreading `BuildConfig.DEBUG` through the UI.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenHarness: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_server_label),
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChange,
            label = { Text(stringResource(R.string.settings_url_label)) },
            placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
            supportingText = { Text(stringResource(R.string.settings_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::save,
                enabled = state.hasUnsavedChanges,
            ) { Text(stringResource(R.string.settings_save)) }

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
            ) { Text(stringResource(R.string.settings_test)) }

            if (state.testing) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (state.justSaved) {
            Text(
                text = stringResource(R.string.settings_saved),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val result = state.testResult) {
            null -> Unit
            TestResult.Succeeded -> ResultCard(
                text = stringResource(R.string.settings_test_ok),
                isError = false,
            )

            is TestResult.Failed -> ResultCard(text = result.message, isError = true)
        }

        Text(
            text = stringResource(R.string.settings_auth_note),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.settings_about_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodySmall,
        )

        // The harness is the only way to validate tag behaviour on real hardware, so it stays
        // reachable in debug builds rather than being deleted. It shows raw hex and trailer
        // bits, so it must not ship to store users.
        if (onOpenHarness != null) {
            OutlinedButton(onClick = onOpenHarness) {
                Text(stringResource(R.string.settings_open_harness))
            }
        }
    }
}

@Composable
private fun ResultCard(text: String, isError: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}
