package ir.aut.supplementtracker.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Screens never read these directly: they go through
 * `MaterialTheme.colorScheme` or `SupplementTheme.statusColors`.
 *
 * Brand: clinical emerald (trust, health) with an indigo accent for
 * "in transit" states and amber for attention.
 */
object SupplementColorTokens {
    // Emerald
    val Emerald10 = Color(0xFF00201C)
    val Emerald20 = Color(0xFF003731)
    val Emerald30 = Color(0xFF005048)
    val Emerald40 = Color(0xFF006A60)
    val Emerald80 = Color(0xFF5EDBC7)
    val Emerald90 = Color(0xFF9EF2E4)

    // Indigo
    val Indigo10 = Color(0xFF00105C)
    val Indigo20 = Color(0xFF08218A)
    val Indigo30 = Color(0xFF293CA0)
    val Indigo40 = Color(0xFF4355B9)
    val Indigo80 = Color(0xFFBAC3FF)
    val Indigo90 = Color(0xFFDEE0FF)

    // Amber
    val Amber10 = Color(0xFF271900)
    val Amber20 = Color(0xFF412D00)
    val Amber30 = Color(0xFF5E4200)
    val Amber40 = Color(0xFF7C5800)
    val Amber80 = Color(0xFFF9BD4A)
    val Amber90 = Color(0xFFFFDEA6)

    // Red
    val Red10 = Color(0xFF410002)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    // Green (success)
    val Green10 = Color(0xFF00391D)
    val Green30 = Color(0xFF0E5230)
    val Green40 = Color(0xFF1B7F4B)
    val Green80 = Color(0xFF7EDB9F)
    val Green90 = Color(0xFFC9F0D6)

    // Blue (information / in transit)
    val Blue10 = Color(0xFF001B3F)
    val Blue30 = Color(0xFF0E3F7F)
    val Blue40 = Color(0xFF2F5DAA)
    val Blue80 = Color(0xFFABC7FF)
    val Blue90 = Color(0xFFD7E3FF)

    // Orange (warning / consumed)
    val Orange10 = Color(0xFF2C1600)
    val Orange30 = Color(0xFF653E00)
    val Orange40 = Color(0xFF8A5300)
    val Orange80 = Color(0xFFFFB95C)
    val Orange90 = Color(0xFFFFDDB3)

    // Emerald-tinted neutrals
    val Neutral4 = Color(0xFF090F0E)
    val Neutral6 = Color(0xFF0E1513)
    val Neutral10 = Color(0xFF171D1B)
    val Neutral12 = Color(0xFF1B2120)
    val Neutral17 = Color(0xFF252B2A)
    val Neutral20 = Color(0xFF2B3230)
    val Neutral22 = Color(0xFF303635)
    val Neutral24 = Color(0xFF343B39)
    val Neutral87 = Color(0xFFD5DBD9)
    val Neutral90 = Color(0xFFDDE4E1)
    val Neutral92 = Color(0xFFE3EAE7)
    val Neutral94 = Color(0xFFE9EFEC)
    val Neutral95 = Color(0xFFECF2EF)
    val Neutral96 = Color(0xFFEFF5F2)
    val Neutral98 = Color(0xFFF5FBF8)
    val Neutral100 = Color(0xFFFFFFFF)

    val NeutralVariant30 = Color(0xFF3F4946)
    val NeutralVariant50 = Color(0xFF6F7976)
    val NeutralVariant60 = Color(0xFF899390)
    val NeutralVariant80 = Color(0xFFBEC9C5)
    val NeutralVariant90 = Color(0xFFDAE5E1)

    // Hero gradient end (deep teal-blue)
    val Lagoon = Color(0xFF0B4F6C)
    val LagoonDark = Color(0xFF0D3346)
}
