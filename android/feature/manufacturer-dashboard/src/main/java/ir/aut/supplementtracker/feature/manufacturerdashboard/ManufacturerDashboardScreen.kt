package ir.aut.supplementtracker.feature.manufacturerdashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ProductListItem
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatTile
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreenPadding
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage

@Composable
fun ManufacturerDashboardScreen(
    state: ManufacturerDashboardUiState,
    onEvent: (ManufacturerDashboardUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters =
        listOf(
            null to stringResource(R.string.dashboard_filter_all),
            "Created" to stringResource(R.string.dashboard_filter_created),
            "Transferred" to stringResource(R.string.dashboard_filter_transferred),
            "AtPointOfSale" to stringResource(R.string.dashboard_filter_pos),
            "Consumed" to stringResource(R.string.dashboard_filter_consumed),
        )
    val unfiltered = state.statusFilter == null && state.search.isBlank()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
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
                        enabled = !state.isLoading && !state.isSubmitting,
                    ) {
                        Icon(
                            imageVector = SupplementIcons.Refresh,
                            contentDescription = stringResource(R.string.dashboard_refresh),
                        )
                    }
                },
            )
        }

        if (unfiltered && state.items.isNotEmpty()) {
            item(key = "stats") {
                val inChain = state.items.count { it.status == "Transferred" || it.status == "AtPointOfSale" }
                val consumed = state.items.count { it.status == "Consumed" }
                Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_units),
                        value = state.items.size.toString(),
                        icon = SupplementIcons.Stock,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_in_chain),
                        value = inChain.toString(),
                        icon = SupplementIcons.Distributor,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.dashboard_stat_consumed),
                        value = consumed.toString(),
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

        item(key = "batch") { BatchCard(state = state, onEvent = onEvent) }

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
                        text = pluralStringResource(R.plurals.dashboard_unit_count, state.items.size, state.items.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
        item(key = "search") {
            SupplementTextField(
                value = state.search,
                onValueChange = { onEvent(ManufacturerDashboardUiEvent.SearchChanged(it)) },
                label = stringResource(R.string.dashboard_search_label),
                leadingIcon = SupplementIcons.Search,
                imeAction = ImeAction.Search,
            )
        }
        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs)) {
                items(filters, key = { it.first ?: "all" }) { (value, label) ->
                    FilterChip(
                        selected = state.statusFilter == value,
                        onClick = { onEvent(ManufacturerDashboardUiEvent.StatusFilterChanged(value)) },
                        label = { Text(text = label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
        if (state.isLoading) {
            item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (state.items.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                EmptyState(
                    icon = SupplementIcons.Stock,
                    title = stringResource(R.string.dashboard_empty),
                    message = stringResource(
                        if (unfiltered) R.string.dashboard_empty_hint else R.string.dashboard_empty_filtered,
                    ),
                )
            }
        }
        items(state.items, key = { it.id }) { item ->
            ProductListItem(
                productId = item.chainProductId,
                title = item.name ?: stringResource(R.string.dashboard_unit_fallback, item.chainProductId),
                subtitle = item.batchCode?.let { stringResource(R.string.dashboard_item_batch, it) },
                status = AuthenticityStatus.fromLifecycle(item.status),
            )
        }
    }
}

@Composable
private fun BatchCard(
    state: ManufacturerDashboardUiState,
    onEvent: (ManufacturerDashboardUiEvent) -> Unit,
) {
    val count = state.batchCount.toIntOrNull() ?: 0
    val countValid = count in 1..100
    SupplementCard {
        SectionHeader(title = stringResource(R.string.dashboard_batch_section))
        Text(
            text = stringResource(R.string.dashboard_batch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SupplementTextField(
            value = state.batchName,
            onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchNameChanged(it)) },
            label = stringResource(R.string.dashboard_batch_name),
            leadingIcon = SupplementIcons.Consume,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
            SupplementTextField(
                value = state.batchCode,
                onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchCodeChanged(it)) },
                label = stringResource(R.string.dashboard_batch_code),
                leadingIcon = SupplementIcons.Receipt,
                modifier = Modifier.weight(1f),
            )
            SupplementTextField(
                value = state.batchCount,
                onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchCountChanged(it)) },
                label = stringResource(R.string.dashboard_batch_count),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                isError = state.batchCount.isNotEmpty() && !countValid,
                modifier = Modifier.width(112.dp),
            )
        }
        SupplementButton(
            text = stringResource(R.string.dashboard_batch_submit),
            onClick = { onEvent(ManufacturerDashboardUiEvent.SubmitBatch) },
            enabled = !state.isSubmitting && state.batchName.isNotBlank() && countValid,
            loading = state.isSubmitting,
            leadingIcon = SupplementIcons.Register,
        )
        if (state.isSubmitting || state.completedCount > 0) {
            val target = state.targetCount.coerceAtLeast(state.completedCount).coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { state.completedCount.toFloat() / target },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.dashboard_batch_progress, state.completedCount, target),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.labelsPdfEnabled) {
            SupplementButton(
                text = stringResource(R.string.dashboard_export_labels),
                onClick = { onEvent(ManufacturerDashboardUiEvent.ExportLabelsPdf) },
                enabled = !state.isExporting && !state.isSubmitting && state.batchCode.isNotBlank(),
                loading = state.isExporting,
                variant = SupplementButtonVariant.Tonal,
                leadingIcon = SupplementIcons.Pdf,
            )
        }
    }
}
