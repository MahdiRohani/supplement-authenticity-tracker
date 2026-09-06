package ir.aut.supplementtracker.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onEvent: (HistoryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.history_title))
        SupplementTextField(
            value = state.productId,
            onValueChange = { onEvent(HistoryUiEvent.ProductIdChanged(it)) },
            label = stringResource(R.string.history_product_id_label),
        )
        SupplementButton(
            text = stringResource(R.string.history_load_action),
            onClick = { onEvent(HistoryUiEvent.Load) },
            enabled = !state.isLoading && state.productId.isNotBlank(),
        )
        if (state.isLoading) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { Text(text = it) }
        state.history?.let { history ->
            StatusChip(status = history.status.toAuthenticityStatus())
            Text(text = stringResource(R.string.history_owner, history.currentOwner))
            Text(text = stringResource(R.string.history_elapsed, history.elapsedMs))
            history.events.forEach { event ->
                Text(
                    text = stringResource(
                        R.string.history_event,
                        event.fromAddress,
                        event.toAddress,
                    ),
                )
            }
        }
    }
}

private fun String.toAuthenticityStatus(): AuthenticityStatus =
    when (this) {
        "Created" -> AuthenticityStatus.Authentic
        "Transferred" -> AuthenticityStatus.Transferred
        "AtPointOfSale" -> AuthenticityStatus.AtPointOfSale
        "Consumed" -> AuthenticityStatus.Consumed
        "Invalid" -> AuthenticityStatus.Invalid
        else -> AuthenticityStatus.NotFound
    }
