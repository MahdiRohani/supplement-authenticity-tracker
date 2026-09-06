package ir.aut.supplementtracker.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField

@Composable
fun TransferScreen(
    state: TransferUiState,
    onEvent: (TransferUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.transfer_title))
        SupplementTextField(
            value = state.productId,
            onValueChange = { onEvent(TransferUiEvent.ProductIdChanged(it)) },
            label = stringResource(R.string.product_id_label),
        )
        SupplementTextField(
            value = state.toAddress,
            onValueChange = { onEvent(TransferUiEvent.ToAddressChanged(it)) },
            label = stringResource(R.string.recipient_label),
        )
        SupplementButton(
            text = stringResource(R.string.transfer_action),
            onClick = { onEvent(TransferUiEvent.Submit) },
            enabled = !state.isSubmitting &&
                state.productId.isNotBlank() &&
                state.toAddress.isNotBlank(),
        )
        if (state.isSubmitting) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { Text(text = it) }
        state.result?.let { result ->
            Text(text = stringResource(R.string.transfer_result, result.txHash))
        }
    }
}
