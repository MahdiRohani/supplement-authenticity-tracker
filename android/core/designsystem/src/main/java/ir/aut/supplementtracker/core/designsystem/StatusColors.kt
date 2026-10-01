package ir.aut.supplementtracker.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A foreground/container pair for one semantic tone. */
@Immutable
data class ToneColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
)

/** Semantic tones that Material's color scheme has no slot for. */
@Immutable
data class SupplementStatusColors(
    val success: ToneColors,
    val info: ToneColors,
    val warning: ToneColors,
    val danger: ToneColors,
    val neutral: ToneColors,
    val heroGradient: List<Color>,
)

internal val LightStatusColors = SupplementStatusColors(
    success = ToneColors(
        accent = SupplementColorTokens.Green40,
        onAccent = SupplementColorTokens.Neutral100,
        container = SupplementColorTokens.Green90,
        onContainer = SupplementColorTokens.Green10,
    ),
    info = ToneColors(
        accent = SupplementColorTokens.Blue40,
        onAccent = SupplementColorTokens.Neutral100,
        container = SupplementColorTokens.Blue90,
        onContainer = SupplementColorTokens.Blue10,
    ),
    warning = ToneColors(
        accent = SupplementColorTokens.Orange40,
        onAccent = SupplementColorTokens.Neutral100,
        container = SupplementColorTokens.Orange90,
        onContainer = SupplementColorTokens.Orange10,
    ),
    danger = ToneColors(
        accent = SupplementColorTokens.Red40,
        onAccent = SupplementColorTokens.Neutral100,
        container = SupplementColorTokens.Red90,
        onContainer = SupplementColorTokens.Red10,
    ),
    neutral = ToneColors(
        accent = SupplementColorTokens.NeutralVariant50,
        onAccent = SupplementColorTokens.Neutral100,
        container = SupplementColorTokens.NeutralVariant90,
        onContainer = SupplementColorTokens.NeutralVariant30,
    ),
    heroGradient = listOf(SupplementColorTokens.Emerald40, SupplementColorTokens.Lagoon),
)

internal val DarkStatusColors = SupplementStatusColors(
    success = ToneColors(
        accent = SupplementColorTokens.Green80,
        onAccent = SupplementColorTokens.Green10,
        container = SupplementColorTokens.Green30,
        onContainer = SupplementColorTokens.Green90,
    ),
    info = ToneColors(
        accent = SupplementColorTokens.Blue80,
        onAccent = SupplementColorTokens.Blue10,
        container = SupplementColorTokens.Blue30,
        onContainer = SupplementColorTokens.Blue90,
    ),
    warning = ToneColors(
        accent = SupplementColorTokens.Orange80,
        onAccent = SupplementColorTokens.Orange10,
        container = SupplementColorTokens.Orange30,
        onContainer = SupplementColorTokens.Orange90,
    ),
    danger = ToneColors(
        accent = SupplementColorTokens.Red80,
        onAccent = SupplementColorTokens.Red20,
        container = SupplementColorTokens.Red30,
        onContainer = SupplementColorTokens.Red90,
    ),
    neutral = ToneColors(
        accent = SupplementColorTokens.NeutralVariant60,
        onAccent = SupplementColorTokens.Neutral6,
        container = SupplementColorTokens.NeutralVariant30,
        onContainer = SupplementColorTokens.NeutralVariant80,
    ),
    heroGradient = listOf(SupplementColorTokens.Emerald30, SupplementColorTokens.LagoonDark),
)

internal val LocalSupplementStatusColors = staticCompositionLocalOf { LightStatusColors }
