package ir.aut.supplementtracker.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.aut.supplementtracker.core.designsystem.SupplementColorTokens
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing

enum class AuthenticityStatus {
    Authentic,
    Transferred,
    AtPointOfSale,
    Consumed,
    Invalid,
    NotFound,
    NetworkError,
}

@Composable
fun StatusChip(
    status: AuthenticityStatus,
    modifier: Modifier = Modifier,
) {
    val (label, background, foreground) = when (status) {
        AuthenticityStatus.Authentic -> Triple(
            "Authentic",
            SupplementColorTokens.Success.copy(alpha = 0.16f),
            SupplementColorTokens.Success,
        )
        AuthenticityStatus.Transferred -> Triple(
            "Transferred",
            SupplementColorTokens.BrandSecondaryContainer,
            SupplementColorTokens.BrandSecondary,
        )
        AuthenticityStatus.AtPointOfSale -> Triple(
            "At point of sale",
            SupplementColorTokens.BrandPrimaryContainer,
            SupplementColorTokens.BrandPrimary,
        )
        AuthenticityStatus.Consumed -> Triple(
            "Consumed",
            SupplementColorTokens.Warning.copy(alpha = 0.18f),
            SupplementColorTokens.Warning,
        )
        AuthenticityStatus.Invalid -> Triple(
            "Invalid",
            SupplementColorTokens.Danger.copy(alpha = 0.16f),
            SupplementColorTokens.Danger,
        )
        AuthenticityStatus.NotFound -> Triple(
            "Not found",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface,
        )
        AuthenticityStatus.NetworkError -> Triple(
            "Network error",
            SupplementColorTokens.Danger.copy(alpha = 0.12f),
            SupplementColorTokens.Danger,
        )
    }

    Box(
        modifier = modifier
            .background(color = background, shape = MaterialTheme.shapes.small)
            .padding(
                horizontal = SupplementSpacing.Sm,
                vertical = SupplementSpacing.Xxs,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
        )
    }
}
