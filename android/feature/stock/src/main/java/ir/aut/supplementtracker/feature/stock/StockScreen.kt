package ir.aut.supplementtracker.feature.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton

@Composable
fun StockScreen(
    state: StockUiState,
    onEvent: (StockUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        when (state.mode) {
            StockMode.Distributor -> stringResource(R.string.stock_title_distributor)
            StockMode.Pharmacy -> stringResource(R.string.stock_title_pharmacy)
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = title)
        SupplementButton(
            text = stringResource(R.string.stock_refresh),
            onClick = { onEvent(StockUiEvent.Refresh) },
            enabled = !state.isLoading,
        )
        if (state.isLoading) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { Text(text = it) }
        if (state.items.isEmpty() && !state.isLoading) {
            Text(text = stringResource(R.string.stock_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs),
            ) {
                items(state.items, key = { it.id }) { item ->
                    Text(
                        text = stringResource(
                            R.string.stock_item_line,
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
