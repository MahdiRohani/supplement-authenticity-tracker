package ir.aut.supplementtracker.feature.manufacturerdashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.dashboard_title))
        if (state.analyticsEnabled) {
            Text(text = stringResource(R.string.dashboard_analytics_title))
            Text(
                text = stringResource(
                    R.string.dashboard_analytics_verify,
                    state.analyticsVerifyCount,
                ),
            )
            Text(
                text = stringResource(
                    R.string.dashboard_analytics_scan,
                    state.analyticsScanCount,
                ),
            )
        }
        SupplementTextField(
            value = state.search,
            onValueChange = { onEvent(ManufacturerDashboardUiEvent.SearchChanged(it)) },
            label = stringResource(R.string.dashboard_search_label),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
        ) {
            filters.forEach { (value, label) ->
                FilterChip(
                    selected = state.statusFilter == value,
                    onClick = {
                        onEvent(ManufacturerDashboardUiEvent.StatusFilterChanged(value))
                    },
                    label = { Text(text = label) },
                    modifier = Modifier.semantics {
                        contentDescription = "Filter $label"
                    },
                )
            }
        }
        SupplementButton(
            text = stringResource(R.string.dashboard_refresh),
            onClick = { onEvent(ManufacturerDashboardUiEvent.Refresh) },
            enabled = !state.isLoading && !state.isSubmitting,
        )
        Text(text = stringResource(R.string.dashboard_batch_section))
        SupplementTextField(
            value = state.batchName,
            onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchNameChanged(it)) },
            label = stringResource(R.string.dashboard_batch_name),
        )
        SupplementTextField(
            value = state.batchCode,
            onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchCodeChanged(it)) },
            label = stringResource(R.string.dashboard_batch_code),
        )
        SupplementTextField(
            value = state.batchCount,
            onValueChange = { onEvent(ManufacturerDashboardUiEvent.BatchCountChanged(it)) },
            label = stringResource(R.string.dashboard_batch_count),
        )
        SupplementButton(
            text = stringResource(R.string.dashboard_batch_submit),
            onClick = { onEvent(ManufacturerDashboardUiEvent.SubmitBatch) },
            enabled = !state.isSubmitting &&
                state.batchName.isNotBlank() &&
                (state.batchCount.toIntOrNull() ?: 0) in 1..100,
        )
        if (state.labelsPdfEnabled) {
            SupplementButton(
                text = stringResource(R.string.dashboard_export_labels),
                onClick = { onEvent(ManufacturerDashboardUiEvent.ExportLabelsPdf) },
                enabled = !state.isExporting &&
                    !state.isSubmitting &&
                    state.batchCode.isNotBlank(),
            )
        }
        if (state.isSubmitting || state.completedCount > 0) {
            Text(
                text = stringResource(
                    R.string.dashboard_batch_progress,
                    state.completedCount,
                    state.targetCount.coerceAtLeast(state.completedCount),
                ),
            )
        }
        if (state.isLoading || state.isSubmitting || state.isExporting) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { Text(text = it) }
        }
        if (state.items.isEmpty() && !state.isLoading) {
            Text(text = stringResource(R.string.dashboard_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
            ) {
                items(state.items, key = { it.id }) { item ->
                    Text(
                        text = stringResource(
                            R.string.dashboard_item_line,
                            item.chainProductId,
                            item.status,
                        ),
                    )
                    item.name?.let { Text(text = it) }
                }
            }
        }
    }
}
