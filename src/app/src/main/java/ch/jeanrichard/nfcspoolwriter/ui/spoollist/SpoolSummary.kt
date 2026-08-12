package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.domain.model.Spool

/**
 * One spool as the swatch and the three lines that go beside it. Shared with the read screen, so the
 * spool a tag points at is presented exactly as the same spool is in the select list — the user is
 * matching one against the other by eye.
 *
 * Carries no padding of its own: the list spaces its own rows, the read screen's card its contents.
 */
@Composable
fun SpoolSummary(spool: Spool, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
