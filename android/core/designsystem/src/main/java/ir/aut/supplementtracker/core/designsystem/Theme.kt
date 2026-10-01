package ir.aut.supplementtracker.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import ir.aut.supplementtracker.core.designsystem.SupplementColorTokens as T

private val LightColors = lightColorScheme(
    primary = T.Emerald40,
    onPrimary = T.Neutral100,
    primaryContainer = T.Emerald90,
    onPrimaryContainer = T.Emerald10,
    inversePrimary = T.Emerald80,
    secondary = T.Indigo40,
    onSecondary = T.Neutral100,
    secondaryContainer = T.Indigo90,
    onSecondaryContainer = T.Indigo10,
    tertiary = T.Amber40,
    onTertiary = T.Neutral100,
    tertiaryContainer = T.Amber90,
    onTertiaryContainer = T.Amber10,
    error = T.Red40,
    onError = T.Neutral100,
    errorContainer = T.Red90,
    onErrorContainer = T.Red10,
    background = T.Neutral98,
    onBackground = T.Neutral10,
    surface = T.Neutral98,
    onSurface = T.Neutral10,
    surfaceVariant = T.NeutralVariant90,
    onSurfaceVariant = T.NeutralVariant30,
    surfaceTint = T.Emerald40,
    inverseSurface = T.Neutral20,
    inverseOnSurface = T.Neutral95,
    outline = T.NeutralVariant50,
    outlineVariant = T.NeutralVariant80,
    scrim = T.Neutral4,
    surfaceBright = T.Neutral98,
    surfaceDim = T.Neutral87,
    surfaceContainerLowest = T.Neutral100,
    surfaceContainerLow = T.Neutral96,
    surfaceContainer = T.Neutral94,
    surfaceContainerHigh = T.Neutral92,
    surfaceContainerHighest = T.Neutral90,
)

private val DarkColors = darkColorScheme(
    primary = T.Emerald80,
    onPrimary = T.Emerald20,
    primaryContainer = T.Emerald30,
    onPrimaryContainer = T.Emerald90,
    inversePrimary = T.Emerald40,
    secondary = T.Indigo80,
    onSecondary = T.Indigo20,
    secondaryContainer = T.Indigo30,
    onSecondaryContainer = T.Indigo90,
    tertiary = T.Amber80,
    onTertiary = T.Amber20,
    tertiaryContainer = T.Amber30,
    onTertiaryContainer = T.Amber90,
    error = T.Red80,
    onError = T.Red20,
    errorContainer = T.Red30,
    onErrorContainer = T.Red90,
    background = T.Neutral6,
    onBackground = T.Neutral90,
    surface = T.Neutral6,
    onSurface = T.Neutral90,
    surfaceVariant = T.NeutralVariant30,
    onSurfaceVariant = T.NeutralVariant80,
    surfaceTint = T.Emerald80,
    inverseSurface = T.Neutral90,
    inverseOnSurface = T.Neutral20,
    outline = T.NeutralVariant60,
    outlineVariant = T.NeutralVariant30,
    scrim = T.Neutral4,
    surfaceBright = T.Neutral24,
    surfaceDim = T.Neutral6,
    surfaceContainerLowest = T.Neutral4,
    surfaceContainerLow = T.Neutral10,
    surfaceContainer = T.Neutral12,
    surfaceContainerHigh = T.Neutral17,
    surfaceContainerHighest = T.Neutral22,
)

@Composable
fun SupplementTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSupplementStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = SupplementTypography,
            shapes = SupplementShapes,
            content = content,
        )
    }
}

/** Design-system values that live outside [MaterialTheme]. */
object SupplementTheme {
    val statusColors: SupplementStatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSupplementStatusColors.current
}
