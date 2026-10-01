package ir.aut.supplementtracker.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SourceSerifFamily = FontFamily(
    Font(R.font.source_serif4_regular, FontWeight.Normal),
    Font(R.font.source_serif4_bold, FontWeight.Bold),
)

private val SourceSansFamily = FontFamily(
    Font(R.font.source_sans3_regular, FontWeight.Normal),
    Font(R.font.source_sans3_semibold, FontWeight.SemiBold),
)

/** Monospace for hashes, addresses and secrets, where every character matters. */
val SupplementMonoFamily: FontFamily = FontFamily.Monospace

private fun sans(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = SourceSansFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// Every Material slot is defined so no component falls back to Roboto.
val SupplementTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SourceSerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = SourceSerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = SourceSerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineLarge = sans(FontWeight.SemiBold, 30, 36),
    headlineMedium = sans(FontWeight.SemiBold, 26, 32),
    headlineSmall = sans(FontWeight.SemiBold, 22, 28),
    titleLarge = sans(FontWeight.SemiBold, 20, 26),
    titleMedium = sans(FontWeight.SemiBold, 16, 22, 0.1),
    titleSmall = sans(FontWeight.SemiBold, 14, 20, 0.1),
    bodyLarge = sans(FontWeight.Normal, 16, 24),
    bodyMedium = sans(FontWeight.Normal, 14, 20),
    bodySmall = sans(FontWeight.Normal, 12, 16, 0.2),
    labelLarge = sans(FontWeight.SemiBold, 14, 20, 0.1),
    labelMedium = sans(FontWeight.SemiBold, 12, 16, 0.4),
    labelSmall = sans(FontWeight.SemiBold, 11, 16, 0.5),
)
