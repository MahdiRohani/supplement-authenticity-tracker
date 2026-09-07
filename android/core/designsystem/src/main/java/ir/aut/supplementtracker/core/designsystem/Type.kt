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

val SupplementTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SourceSerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SourceSerifFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SourceSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SourceSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = SourceSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SourceSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)
