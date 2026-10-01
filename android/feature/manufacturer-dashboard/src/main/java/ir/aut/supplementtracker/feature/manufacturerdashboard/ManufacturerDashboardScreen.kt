package ir.aut.supplementtracker.feature.manufacturerdashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.ImeAction
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementMonoFamily
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatTile
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreenPadding
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail

@Composable
fun ManufacturerDashboardScreen(
    state: ManufacturerDashboardUiState,
    onEvent: (ManufacturerDashboardUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.visibleItems
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("dashboard_screen"),
        contentPadding = SupplementScreenPadding,
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Md),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.dashboard_title),
                subtitle = stringResource(R.string.dashboard_subtitle),
                icon = SupplementIcons.Dashboard,
                trailing = {
                    IconButton(
                        onClick = { onEvent(ManufacturerDashboardUiEvent.Refresh) },
                        enabled = !state.isLoading,
                    ) {
                        Icon(
                            imageVector = SupplementIcons.Refresh,
                            contentDescription = stringResource(R.string.dashboard_refresh),
                        )
                    }
                },
            )
        }

        if (state.items.isNotEmpty()) {
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_batches),
                        value = state.items.size.toString(),
                        icon = SupplementIcons.Stock,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_units),
                        value = state.totalUnits.toString(),
                        icon = SupplementIcons.Distributor,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_consumed),
                        value = state.consumedUnits.toString(),
                        icon = SupplementIcons.Consume,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (state.analyticsEnabled) {
            item(key = "analytics") {
                Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
                    StatTile(
                        label = stringResource(R.string.dashboard_analytics_verify),
                        value = state.analyticsVerifyCount.toString(),
                        icon = SupplementIcons.Verify,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_analytics_scan),
                        value = state.analyticsScanCount.toString(),
                        icon = SupplementIcons.Scan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item(key = "new") {
            SupplementButton(
                text = stringResource(R.string.dashboard_new_batch),
                onClick = { onEvent(ManufacturerDashboardUiEvent.NewBatch) },
                leadingIcon = SupplementIcons.Register,
                modifier = Modifier.testTag("dashboard_new_batch"),
            )
        }

        if (state.recalledBatches > 0) {
            item(key = "recalled") {
                NoticeCard(
                    message = pluralStringResource(
                        R.plurals.dashboard_recalled_notice,
                        state.recalledBatches,
                        state.recalledBatches,
                    ),
                    tone = NoticeTone.Warning,
                    icon = SupplementIcons.Blocked,
                )
            }
        }

        state.errorMessage?.let { raw ->
            item(key = "error") {
                localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
            }
        }

        item(key = "inventoryHeader") {
            SectionHeader(
                title = stringResource(R.string.dashboard_inventory),
                trailing = {
                    Text(
                        text = pluralStringResource(R.plurals.dashboard_batch_count, visible.size, visible.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        if (state.items.isNotEmpty()) {
            item(key = "search") {
                SupplementTextField(
                    value = state.search,
                    onValueChange = { onEvent(ManufacturerDashboardUiEvent.SearchChanged(it)) },
                    label = stringResource(R.string.dashboard_search_label),
                    leadingIcon = SupplementIcons.Search,
                    imeAction = ImeAction.Search,
                )
            }
        }
        if (state.isLoading) {
            item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (visible.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                EmptyState(
                    icon = SupplementIcons.Stock,
                    title = stringResource(R.string.dashboard_empty),
                    message = stringResource(
                        if (state.items.isEmpty()) R.string.dashboard_empty_hint else R.string.dashboard_empty_filtered,
                    ),
                )
            }
        }
        items(visible, key = { it.batchId }) { batch ->
            val selected = state.selectedBatchId == batch.batchId
            BatchItem(
                batch = batch,
                selected = selected,
                detail = state.detail?.takeIf { selected && it.batch.batchId == batch.batchId },
                loadingDetail = selected && state.isLoadingDetail,
                onClick = { onEvent(ManufacturerDashboardUiEvent.BatchSelected(batch.batchId)) },
            )
        }
    }
}

@Composable
private fun BatchItem(
    batch: Batch,
    selected: Boolean,
    detail: BatchDetail?,
    loadingDetail: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_batch_${batch.batchId}"),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(SupplementSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = batch.name ?: stringResource(R.string.dashboard_batch_fallback, batch.batchId),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.dashboard_batch_subtitle,
                            batch.batchId,
                            batch.lotCode ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (batch.recalled) StatusChip(status = AuthenticityStatus.Recalled)
            }
            LinearProgressIndicator(
                progress = { if (batch.size == 0) 0f else batch.consumedCount.toFloat() / batch.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.dashboard_batch_consumed, batch.consumedCount, batch.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(visible = selected) {
                Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (loadingDetail && detail == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    detail?.let { BatchDistribution(it) }
                }
            }
        }
    }
}

@Composable
private fun BatchDistribution(detail: BatchDetail) {
    Text(
        text = stringResource(R.string.dashboard_distribution),
        style = MaterialTheme.typography.titleSmall,
    )
    detail.distribution.entries.sortedByDescending { it.value }.forEach { (owner, units) ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = owner.shortenMiddle(8, 6),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SupplementMonoFamily),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.dashboard_unit_count, units, units),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    Text(
        text = stringResource(R.string.dashboard_segments),
        style = MaterialTheme.typography.titleSmall,
    )
    detail.segments.sortedBy { it.start }.forEach { segment ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_segment_range, segment.start, segment.lastIndex),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = segment.owner.shortenMiddle(8, 6),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = SupplementMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(status = AuthenticityStatus.fromLifecycle(segment.status))
        }
    }
}
