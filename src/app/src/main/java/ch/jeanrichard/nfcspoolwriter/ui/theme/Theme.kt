package ch.jeanrichard.nfcspoolwriter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Amber40,
    onPrimary = Neutral100,
    primaryContainer = Amber90,
    onPrimaryContainer = Amber10,
    inversePrimary = Amber80,
    secondary = Cyan40,
    onSecondary = Neutral100,
    secondaryContainer = Cyan90,
    onSecondaryContainer = Cyan10,
    tertiary = Blue40,
    onTertiary = Neutral100,
    tertiaryContainer = Blue90,
    onTertiaryContainer = Blue10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceDim = NeutralDim,
    surfaceBright = Neutral98,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = NeutralContainerLow,
    surfaceContainer = NeutralContainer,
    surfaceContainerHigh = NeutralContainerHigh,
    surfaceContainerHighest = NeutralContainerHighest,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Amber80,
    onPrimary = Amber10,
    primaryContainer = Amber30,
    onPrimaryContainer = Amber90,
    inversePrimary = Amber40,
    secondary = Cyan80,
    onSecondary = Cyan10,
    secondaryContainer = Cyan30,
    onSecondaryContainer = Cyan90,
    tertiary = Blue80,
    onTertiary = Blue10,
    tertiaryContainer = Blue30,
    onTertiaryContainer = Blue90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceDim = Neutral6,
    surfaceBright = Neutral24,
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Color.Black,
)

/**
 * @param dynamicColor when true, Android 12+ derives the scheme from the user's wallpaper and the
 *   palette above is unused. Off by default so the app looks the same on every device: the store
 *   screenshots have to be reproducible, and an app whose accent is amber in the listing but green
 *   on the reviewer's phone looks unfinished. Callers that want Material You can opt back in.
 */
@Composable
fun NfcSpoolWriterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
