package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpoolListScreen(
    viewModel: SpoolListViewModel,
    onSpoolSelected: (Int) -> Unit,
    onConfigureServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(R.string.spools_search_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            // First run: not an error, so it gets a call to action instead of a red message.
            state.notConfigured -> CenteredBox {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.spools_not_configured),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onConfigureServer) {
                        Text(stringResource(R.string.spools_configure))
                    }
                }
            }

            // Nothing loaded at all, so there is no list to pull down on: this failure keeps an
            // explicit retry. A failure over a list we still have is reported inside it instead.
            state.error != null && state.spools.isEmpty() -> CenteredBox {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::load) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }

            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.error?.let { message ->
                        item {
                            // The pull gesture is the retry, so this needs no button of its own.
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (state.truncated) {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.spools_truncated,
                                    state.spools.size,
                                    state.totalCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (state.isEmptyResult) {
                        // Inside the list rather than replacing it, so an empty result can still be
                        // pulled down to reload.
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (state.hasNoSpoolsAtAll) R.string.spools_none
                                        else R.string.spools_no_match
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    items(state.visibleSpools, key = { it.id }) { spool ->
                        SpoolRow(spool = spool, onClick = { onSpoolSelected(spool.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SpoolRow(spool: Spool, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpoolSwatch(filament = spool.filament)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = spool.filament.name
                    ?: spool.filament.material
                    ?: stringResource(R.string.spools_unnamed),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = listOfNotNull(
                    spool.filament.vendor?.name,
                    // The swatch already carries the material, so repeating it here would only be
                    // noise — unless it was too long to fit and got cut short.
                    spool.filament.material.takeIf { swatchLabel(it).truncated },
                    spool.filament.fullSpoolWeightGrams?.let { "${it.toInt()} g" },
                    spool.location,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.spools_id, spool.id),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
