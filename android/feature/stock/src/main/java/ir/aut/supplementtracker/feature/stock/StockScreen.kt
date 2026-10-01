package ir.aut.supplementtracker.feature.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreenPadding
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.Segment

@Composable
fun StockScreen(
    state: StockUiState,
    onEvent: (StockUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, icon) =
        when (state.mode) {
            StockMode.Distributor -> stringResource(R.string.stock_title_distributor) to SupplementIcons.Distributor
            StockMode.Pharmacy -> stringResource(R.string.stock_title_pharmacy) to SupplementIcons.Pharmacy
        }
    val refreshLabel = stringResource(R.string.stock_refresh)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("stock_screen"),
        contentPadding = SupplementScreenPadding,
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = title,
                subtitle = state.ownerAddress.takeIf { it.isNotBlank() }?.let {
                    stringResource(R.string.stock_owner, it.shortenMiddle())
                },
                icon = icon,
                trailing = {
                    IconButton(onClick = { onEvent(StockUiEvent.Refresh) }, enabled = !state.isLoading) {
                        Icon(imageVector = SupplementIcons.Refresh, contentDescription = refreshLabel)
                    }
                },
            )
        }
        state.errorMessage?.let { raw ->
            item(key = "error") {
                localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
            }
        }
        item(key = "count") {
            SectionHeader(
                title = stringResource(R.string.stock_section),
                trailing = {
                    Text(
                        text = pluralStringResource(R.plurals.stock_unit_count, state.totalUnits, state.totalUnits),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (state.isLoading) {
            item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (state.items.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                EmptyState(
                    icon = SupplementIcons.Stock,
                    title = stringResource(R.string.stock_empty),
                    message = stringResource(
                        when (state.mode) {
                            StockMode.Distributor -> R.string.stock_empty_hint_distributor
                            StockMode.Pharmacy -> R.string.stock_empty_hint_pharmacy
                        },
                    ),
                ) {
                    SupplementButton(
                        text = refreshLabel,
                        onClick = { onEvent(StockUiEvent.Refresh) },
                        variant = SupplementButtonVariant.Tonal,
                        leadingIcon = SupplementIcons.Refresh,
                        fillWidth = false,
                    )
                }
            }
        }
        items(state.items, key = { it.segmentId }) { segment ->
            SegmentItem(
                segment = segment,
                onTransfer = if (state.canTransfer) {
                    { onEvent(StockUiEvent.TransferSegment(segment.segmentId)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun SegmentItem(
    segment: Segment,
    onTransfer: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stock_segment_${segment.segmentId}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(SupplementSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = segment.batchName ?: stringResource(R.string.stock_batch_fallback, segment.batchId),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.stock_segment_subtitle,
                            segment.batchId,
                            segment.lotCode ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(status = AuthenticityStatus.fromLifecycle(segment.status))
            }
            Text(
                text = stringResource(R.string.stock_segment_range, segment.start, segment.lastIndex) + " · " +
                    pluralStringResource(R.plurals.stock_unit_count, segment.units, segment.units),
                style = MaterialTheme.typography.labelLarge,
            )
            onTransfer?.let {
                SupplementButton(
                    text = stringResource(R.string.stock_transfer),
                    onClick = it,
                    variant = SupplementButtonVariant.Tonal,
                    leadingIcon = SupplementIcons.Transfer,
                    modifier = Modifier.testTag("stock_transfer_${segment.segmentId}"),
                )
            }
        }
    }
}
