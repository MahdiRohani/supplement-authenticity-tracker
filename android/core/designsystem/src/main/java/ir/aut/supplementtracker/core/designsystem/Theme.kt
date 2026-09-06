package ir.aut.supplementtracker.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SupplementColorTokens.BrandPrimary,
    onPrimary = SupplementColorTokens.BrandOnPrimary,
    primaryContainer = SupplementColorTokens.BrandPrimaryContainer,
    secondary = SupplementColorTokens.BrandSecondary,
    secondaryContainer = SupplementColorTokens.BrandSecondaryContainer,
    surface = SupplementColorTokens.Surface,
    onSurface = SupplementColorTokens.OnSurface,
    outline = SupplementColorTokens.Outline,
    error = SupplementColorTokens.Danger,
)

private val DarkColors = darkColorScheme(
    primary = SupplementColorTokens.BrandPrimaryContainer,
    onPrimary = SupplementColorTokens.OnSurface,
    primaryContainer = SupplementColorTokens.BrandPrimary,
    secondary = SupplementColorTokens.BrandSecondaryContainer,
    secondaryContainer = SupplementColorTokens.BrandSecondary,
    surface = SupplementColorTokens.SurfaceDark,
    onSurface = SupplementColorTokens.OnSurfaceDark,
    outline = SupplementColorTokens.Outline,
    error = SupplementColorTokens.Danger,
)

@Composable
fun SupplementTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SupplementTypography,
        shapes = SupplementShapes,
        content = content,
    )
}
