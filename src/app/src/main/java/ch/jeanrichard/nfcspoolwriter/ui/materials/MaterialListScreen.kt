package ch.jeanrichard.nfcspoolwriter.ui.materials

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialEntry
import ch.jeanrichard.nfcspoolwriter.domain.model.MaterialSource

/**
 * The material catalog. Tapping an entry edits it; the FAB adds one.
 *
 * Deprecated entries are shown dimmed rather than hidden: they are still part of what a tag can
 * carry, and a user looking for "Hyper PLA" should find both and see which one is current.
 */
@Composable
fun MaterialListScreen(
    viewModel: MaterialListViewModel,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.materials_search_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                !state.loaded -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.isEmptyResult -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.materials_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.visibleEntries, key = { it.id }) { entry ->
                        MaterialRow(entry = entry, onClick = { onEdit(entry.id) })
                        HorizontalDivider()
                    }
                    if (state.hasUserChanges) {
                        item(key = "reset") {
                            TextButton(
                                onClick = viewModel::requestReset,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            ) { Text(stringResource(R.string.materials_reset)) }
                        }
                    }
                    // Keeps the last row clear of the FAB.
                    item(key = "fab-space") { Spacer(modifier = Modifier.height(88.dp)) }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.materials_add),
            )
        }
    }

    if (state.confirmingReset) {
        AlertDialog(
            onDismissRequest = viewModel::cancelReset,
            title = { Text(stringResource(R.string.materials_reset_title)) },
            text = { Text(stringResource(R.string.materials_reset_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReset) {
                    Text(stringResource(R.string.materials_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelReset) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MaterialRow(entry: MaterialEntry, onClick: () -> Unit) {
    val textColor = if (entry.deprecated) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FamilyTile(family = entry.type)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(entry.brand) }
                    append(" · ")
                    append(entry.id)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val badge = when {
            entry.deprecated -> R.string.materials_deprecated
            entry.source == MaterialSource.CUSTOM -> R.string.materials_badge_added
            entry.source == MaterialSource.EDITED -> R.string.materials_badge_edited
            else -> null
        }
        if (badge != null) {
            SuggestionChip(onClick = onClick, label = { Text(stringResource(badge)) })
        }
    }
}

/**
 * The family is what matching and print temperatures hang on, so it leads the row; a missing one is
 * drawn in the error colours because it means the material can never be matched.
 */
@Composable
private fun FamilyTile(family: String?) {
    val noFamily = stringResource(R.string.materials_no_family)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (family == null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier
            .size(width = 80.dp, height = 40.dp)
            .semantics { if (family == null) contentDescription = noFamily },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = family ?: stringResource(R.string.materials_family_unknown),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
