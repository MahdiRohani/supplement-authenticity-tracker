package ir.aut.supplementtracker.feature.manufacturerregister

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing

@Composable
fun ManufacturerRegisterScreen(
    state: ManufacturerRegisterUiState,
    onEvent: (ManufacturerRegisterUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.manufacturer_register_title))
        OutlinedTextField(
            value = state.name,
            onValueChange = { onEvent(ManufacturerRegisterUiEvent.NameChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.product_name_label)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.batch,
            onValueChange = { onEvent(ManufacturerRegisterUiEvent.BatchChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.batch_label)) },
            singleLine = true,
        )
        Button(
            onClick = { onEvent(ManufacturerRegisterUiEvent.Submit) },
            enabled = !state.isSubmitting && state.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.register_action))
        }
        if (state.isSubmitting) {
            CircularProgressIndicator()
        }
        state.errorMessage?.let { Text(text = it) }
        state.result?.let { product ->
            Text(text = stringResource(R.string.result_id, product.chainProductId))
            product.metadataCid?.let { cid ->
                Text(text = stringResource(R.string.result_cid, cid))
            }
            product.secret?.let { secret ->
                Text(text = stringResource(R.string.result_secret, secret))
            }
        }
    }
}
