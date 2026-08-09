package ch.jeanrichard.nfcspoolwriter.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Tones derived from the launcher icon (res/values/colors.xml): amber carries the filament, cyan the
 * contactless signal, and a muted blue echoes the icon's ground.
 *
 * The numbers follow the Material 3 tonal convention — an accent uses tone 40 in a light scheme and
 * tone 80 in a dark one, each paired with a container and an `on` colour far enough away to stay
 * legible. Keeping that convention is what lets the two schemes be written by pairing tones rather
 * than by eye.
 */

// Amber — primary. The filament.
val Amber10 = Color(0xFF2C1600)
val Amber30 = Color(0xFF683C00)
val Amber40 = Color(0xFF8A5100)
val Amber80 = Color(0xFFFFB951)
val Amber90 = Color(0xFFFFDDB6)

// Cyan — secondary. The signal.
val Cyan10 = Color(0xFF001F28)
val Cyan30 = Color(0xFF004C60)
val Cyan40 = Color(0xFF00657D)
val Cyan80 = Color(0xFF5AD5F0)
val Cyan90 = Color(0xFFB4EBFF)

// Blue — tertiary. The icon's ground.
val Blue10 = Color(0xFF001C38)
val Blue30 = Color(0xFF1E4875)
val Blue40 = Color(0xFF38608F)
val Blue80 = Color(0xFFA2C9FE)
val Blue90 = Color(0xFFD3E4FF)

/*
 * Neutrals, carrying a slight blue bias toward the icon's ground.
 *
 * Material 3's baseline neutrals are tinted lavender — measured on device, body text came out
 * #E6E1E5. Against an amber accent that reads as a faint colour clash rather than as a decision, so
 * every surface and text role below is set explicitly instead of inheriting the baseline.
 */
val Neutral4 = Color(0xFF0B0E13)
val Neutral6 = Color(0xFF101319)
val Neutral10 = Color(0xFF14171D)
val Neutral12 = Color(0xFF181B21)
val Neutral17 = Color(0xFF22252B)
val Neutral20 = Color(0xFF292C33)
val Neutral22 = Color(0xFF2D3037)
val Neutral24 = Color(0xFF313339)
val Neutral90 = Color(0xFFDDE1E9)
val Neutral95 = Color(0xFFECEFF7)
val Neutral98 = Color(0xFFF7FAFF)
val Neutral100 = Color(0xFFFFFFFF)

// Light-scheme container steps, tinted the same way.
val NeutralDim = Color(0xFFD8DAE2)
val NeutralContainerLow = Color(0xFFF2F3FA)
val NeutralContainer = Color(0xFFECEEF5)
val NeutralContainerHigh = Color(0xFFE6E8EF)
val NeutralContainerHighest = Color(0xFFE0E2E9)

// Neutral-variant — a touch more chroma, for outlines and secondary text.
val NeutralVariant30 = Color(0xFF3C4450)
val NeutralVariant50 = Color(0xFF6C7480)
val NeutralVariant60 = Color(0xFF868E9B)
val NeutralVariant80 = Color(0xFFBFC7D5)
val NeutralVariant90 = Color(0xFFDBE3F1)
