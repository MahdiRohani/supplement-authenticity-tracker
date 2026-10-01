package ir.aut.supplementtracker.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.R
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.ToneColors

enum class AuthenticityStatus {
    Authentic,
    Created,
    Transferred,
    AtPointOfSale,
    Consumed,
    Invalid,
    NotFound,
    NetworkError,
    ;

    companion object {
        /** Maps a backend lifecycle status (`Created`, `Transferred`, …) to its visual state. */
        fun fromLifecycle(raw: String?): AuthenticityStatus =
            when (raw) {
                "Created" -> Created
                "Transferred" -> Transferred
                "AtPointOfSale" -> AtPointOfSale
                "Consumed" -> Consumed
                "Invalid" -> Invalid
                else -> NotFound
            }
    }
}

private data class StatusVisual(
    val tone: ToneColors,
    val icon: ImageVector,
    val label: String,
    val description: String,
)

@Composable
private fun AuthenticityStatus.visual(): StatusVisual {
    val colors = SupplementTheme.statusColors
    val brand = ToneColors(
        accent = MaterialTheme.colorScheme.primary,
        onAccent = MaterialTheme.colorScheme.onPrimary,
        container = MaterialTheme.colorScheme.primaryContainer,
        onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    return when (this) {
        AuthenticityStatus.Authentic -> StatusVisual(
            colors.success,
            SupplementIcons.Verified,
            stringResource(R.string.status_authentic),
            stringResource(R.string.status_authentic_desc),
        )
        AuthenticityStatus.Created -> StatusVisual(
            brand,
            SupplementIcons.Register,
            stringResource(R.string.status_created),
            stringResource(R.string.status_created_desc),
        )
        AuthenticityStatus.Transferred -> StatusVisual(
            colors.info,
            SupplementIcons.Distributor,
            stringResource(R.string.status_transferred),
            stringResource(R.string.status_transferred_desc),
        )
        AuthenticityStatus.AtPointOfSale -> StatusVisual(
            colors.success,
            SupplementIcons.Pharmacy,
            stringResource(R.string.status_at_pos),
            stringResource(R.string.status_at_pos_desc),
        )
        AuthenticityStatus.Consumed -> StatusVisual(
            colors.warning,
            SupplementIcons.Warning,
            stringResource(R.string.status_consumed),
            stringResource(R.string.status_consumed_desc),
        )
        AuthenticityStatus.Invalid -> StatusVisual(
            colors.danger,
            SupplementIcons.Blocked,
            stringResource(R.string.status_invalid),
            stringResource(R.string.status_invalid_desc),
        )
        AuthenticityStatus.NotFound -> StatusVisual(
            colors.neutral,
            SupplementIcons.Unknown,
            stringResource(R.string.status_not_found),
            stringResource(R.string.status_not_found_desc),
        )
        AuthenticityStatus.NetworkError -> StatusVisual(
            colors.danger,
            SupplementIcons.Offline,
            stringResource(R.string.status_network_error),
            stringResource(R.string.status_network_error_desc),
        )
    }
}

/** Compact status pill for lists and summaries. */
@Composable
fun StatusChip(
    status: AuthenticityStatus,
    modifier: Modifier = Modifier,
) {
    val visual = status.visual()
    val cd = stringResource(R.string.cd_status, visual.label)
    Row(
        modifier = modifier
            .semantics { contentDescription = cd }
            .background(color = visual.tone.container, shape = CircleShape)
            .padding(horizontal = SupplementSpacing.Sm, vertical = SupplementSpacing.Xxs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Xxs),
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.tone.onContainer,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = visual.label,
            style = MaterialTheme.typography.labelMedium,
            color = visual.tone.onContainer,
        )
    }
}

/** Large verdict card: the answer to "can I trust this unit?". */
@Composable
fun StatusHero(
    status: AuthenticityStatus,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val visual = status.visual()
    val cd = stringResource(R.string.cd_status, visual.label)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = cd },
        shape = MaterialTheme.shapes.large,
        color = visual.tone.container,
    ) {
        Row(
            modifier = Modifier.padding(SupplementSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                icon = visual.icon,
                containerColor = visual.tone.accent,
                contentColor = visual.tone.onAccent,
                size = 56.dp,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xxs),
            ) {
                Text(
                    text = visual.label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = visual.tone.onContainer,
                )
                Text(
                    text = message?.takeIf { it.isNotBlank() } ?: visual.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = visual.tone.onContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
