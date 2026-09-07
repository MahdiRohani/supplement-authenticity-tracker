package ir.aut.supplementtracker.feature.consume

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
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField

@Composable
fun ConsumeScreen(
    state: ConsumeUiState,
    onEvent: (ConsumeUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.consume_title))
        SupplementTextField(
            value = state.productId,
            onValueChange = { onEvent(ConsumeUiEvent.ProductIdChanged(it)) },
            label = stringResource(R.string.consume_product_id_label),
        )
        SupplementTextField(
            value = state.secret,
            onValueChange = { onEvent(ConsumeUiEvent.SecretChanged(it)) },
            label = stringResource(R.string.consume_secret_label),
        )
        SupplementButton(
            text = stringResource(R.string.consume_action),
            onClick = { onEvent(ConsumeUiEvent.Submit) },
            enabled = !state.isSubmitting &&
                state.productId.isNotBlank() &&
                state.secret.isNotBlank(),
        )
        if (state.isSubmitting) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { Text(text = it) }
        state.result?.let { result ->
            StatusChip(status = AuthenticityStatus.Consumed)
            Text(text = stringResource(R.string.consume_result_status, result.status))
            Text(text = stringResource(R.string.consume_result_tx, result.txHash))
        }
    }
}
