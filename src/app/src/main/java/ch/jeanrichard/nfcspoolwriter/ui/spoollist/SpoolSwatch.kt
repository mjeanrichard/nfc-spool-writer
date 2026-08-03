package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.jeanrichard.nfcspoolwriter.R
import ch.jeanrichard.nfcspoolwriter.domain.model.Filament
import kotlin.math.pow

/**
 * The colour chip that leads every spool row: the filament's own colour, with its material set into
 * it, so a list can be scanned by eye instead of read line by line.
 *
 * The presentation decisions all live in the pure functions below rather than in the composable, as
 * this project has no Compose UI tests — see the module's test sources.
 */
@Composable
fun SpoolSwatch(filament: Filament, modifier: Modifier = Modifier) {
    val label = swatchLabel(filament.material)
    val rgb = parseSwatchColor(filament.colorHex)
    val description = swatchDescription(filament)

    val fill = rgb?.let { Color(it or OPAQUE) } ?: MaterialTheme.colorScheme.surfaceVariant
    val content = when {
        rgb == null -> MaterialTheme.colorScheme.onSurfaceVariant
        prefersDarkText(rgb) -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = modifier
            .size(SWATCH_SIZE_DP.dp)
            .background(fill, RoundedCornerShape(SWATCH_CORNER_DP.dp))
            // Always outlined: an unbordered white or near-black chip disappears into the list
            // background of the matching theme.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(SWATCH_CORNER_DP.dp))
            // The label duplicates the row text; the colour is what a screen reader cannot see.
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.text,
            color = content,
            fontSize = label.fontSizeSp.sp,
            lineHeight = label.fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun swatchDescription(filament: Filament): String {
    val material = filament.material?.trim()?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.spools_swatch_unknown_material)
    val colour = parseSwatchColor(filament.colorHex)
    return if (colour == null) {
        stringResource(R.string.spools_swatch_no_colour, material)
    } else {
        stringResource(R.string.spools_swatch_colour, material, "%06X".format(colour))
    }
}

/** What goes inside the chip, and how big it has to be drawn to fit. */
data class SwatchLabel(
    val text: String,
    val fontSizeSp: Float,
    /** The material had to be shortened, so the row still has to spell it out. */
    val truncated: Boolean,
)

private const val SWATCH_SIZE_DP = 48
private const val SWATCH_CORNER_DP = 8

/**
 * Widest label the chip can hold at its smallest step. Longer materials are cut rather than shrunk
 * further, because below this size the text stops being readable at arm's length.
 */
private const val MAX_LABEL_CHARS = 7

/**
 * Fits the material to the chip by stepping the font down, so the common short names (`PLA`, `PETG`)
 * stay large and only the long ones pay for their length. A step table rather than a measured
 * auto-size: the chip is a fixed width, so the outcome is the same and this one is testable.
 */
fun swatchLabel(material: String?): SwatchLabel {
    val normalized = material?.trim()?.replace(WHITESPACE, " ")?.uppercase().orEmpty()
    if (normalized.isEmpty()) return SwatchLabel(UNKNOWN_MATERIAL_LABEL, 13f, truncated = false)

    val truncated = normalized.length > MAX_LABEL_CHARS
    val text = if (truncated) normalized.take(MAX_LABEL_CHARS - 1) + "…" else normalized
    val fontSizeSp = when (text.length) {
        in 0..4 -> 13f
        in 5..6 -> 11f
        else -> 9f
    }
    return SwatchLabel(text, fontSizeSp, truncated)
}

/**
 * The filament colour as `0xRRGGBB`, or `null` when Spoolman has none to give. Accepts the same
 * shapes the tag mapping does — a leading `#`, either case, 3-digit shorthand, or 8 digits whose
 * alpha is dropped — but reports "no colour" instead of substituting a default, since a chip showing
 * a made-up colour would be a lie about the spool.
 */
fun parseSwatchColor(hex: String?): Int? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    if (raw.isEmpty() || !raw.all { it.isHexDigit() }) return null
    val rgb = when (raw.length) {
        6 -> raw
        8 -> raw.take(6)
        3 -> raw.map { "$it$it" }.joinToString("")
        else -> return null
    }
    return rgb.toInt(16)
}

/**
 * Whether black text reads better than white on this colour, by WCAG relative luminance. The 0.5
 * threshold sits where the two contrast ratios cross, so whichever side a colour falls on is the
 * more legible of the pair.
 */
fun prefersDarkText(rgb: Int): Boolean {
    val luminance = 0.2126 * linearize((rgb shr 16) and 0xFF) +
        0.7152 * linearize((rgb shr 8) and 0xFF) +
        0.0722 * linearize(rgb and 0xFF)
    return luminance > 0.5
}

private fun linearize(channel: Int): Double {
    val v = channel / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private const val OPAQUE = 0xFF000000.toInt()
private const val UNKNOWN_MATERIAL_LABEL = "?"
private val WHITESPACE = Regex("\\s+")

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
