package ir.aut.supplementtracker.feature.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ProductListItem
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreenPadding
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage

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
        modifier = modifier.fillMaxSize(),
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
                        text = pluralStringResource(R.plurals.stock_unit_count, state.items.size, state.items.size),
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
        items(state.items, key = { it.id }) { item ->
            ProductListItem(
                productId = item.chainProductId,
                title = item.name ?: stringResource(R.string.stock_unit_fallback, item.chainProductId),
                subtitle = item.batchCode?.let { stringResource(R.string.stock_item_batch, it) },
                status = AuthenticityStatus.fromLifecycle(item.status),
            )
        }
    }
}
