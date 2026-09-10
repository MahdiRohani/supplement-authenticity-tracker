package ir.aut.supplementtracker.feature.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField

@Composable
fun VerifyScreen(
    state: VerifyUiState,
    onEvent: (VerifyUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SupplementSpacing.Md)
            .testTag("verify_screen"),
        verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Text(text = stringResource(R.string.verify_title))
        Text(text = stringResource(R.string.verify_guest_hint))
        SupplementTextField(
            value = state.input,
            onValueChange = { onEvent(VerifyUiEvent.InputChanged(it)) },
            label = stringResource(R.string.verify_input_label),
            modifier = Modifier.testTag("verify_input"),
        )
        SupplementButton(
            text = stringResource(R.string.verify_action),
            onClick = { onEvent(VerifyUiEvent.Submit) },
            enabled = !state.isLoading && state.input.isNotBlank(),
            modifier = Modifier.testTag("verify_submit"),
        )
        if (state.scanEnabled) {
            SupplementButton(
                text = stringResource(R.string.verify_scan_qr),
                onClick = { onEvent(VerifyUiEvent.ScanQr) },
                enabled = !state.isLoading,
                modifier = Modifier.testTag("verify_scan"),
            )
        }
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.testTag("verify_loading"))
        }
        state.authenticityStatus?.let { status ->
            StatusChip(
                status = status,
                modifier = Modifier.testTag("verify_status_${status.name}"),
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let {
                Text(text = it, modifier = Modifier.testTag("verify_error"))
            }
        }
        state.result?.let { result ->
            Text(text = stringResource(R.string.verify_owner, result.currentOwner))
            result.metadata?.name?.let {
                Text(text = stringResource(R.string.verify_name, it))
            }
            result.metadata?.batch?.let {
                Text(text = stringResource(R.string.verify_batch, it))
            }
            result.metadata?.expiresAt?.let {
                Text(text = stringResource(R.string.verify_expires, it))
            }
            result.metadataGatewayUrl?.let {
                Text(text = stringResource(R.string.verify_metadata_link, it))
            }
            Text(text = stringResource(R.string.verify_source, result.source))
            if (state.reportsEnabled) {
                SupplementButton(
                    text = stringResource(R.string.verify_report_counterfeit),
                    onClick = { onEvent(VerifyUiEvent.ReportCounterfeit) },
                    enabled = !state.isReporting && !state.isLoading,
                    modifier = Modifier.testTag("verify_report"),
                )
            }
        }
    }
}

@Composable
fun VerifyStatusPreview(status: AuthenticityStatus) {
    StatusChip(status = status, modifier = Modifier.testTag("preview_${status.name}"))
}
