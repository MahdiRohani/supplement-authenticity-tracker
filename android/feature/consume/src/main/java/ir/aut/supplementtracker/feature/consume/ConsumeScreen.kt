package ir.aut.supplementtracker.feature.consume

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.StatusHero
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage

@Composable
fun ConsumeScreen(
    state: ConsumeUiState,
    onEvent: (ConsumeUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SupplementScreen(modifier = modifier.testTag("consume_screen")) {
        ScreenHeader(
            title = stringResource(R.string.consume_title),
            subtitle = stringResource(R.string.consume_subtitle),
            icon = SupplementIcons.Consume,
        )
        NoticeCard(
            message = stringResource(R.string.consume_irreversible),
            tone = NoticeTone.Warning,
        )
        state.expected?.let {
            NoticeCard(
                message = stringResource(R.string.consume_expected, it.batchId, it.index),
                tone = NoticeTone.Info,
                icon = SupplementIcons.Verified,
            )
        }
        if (state.result == null) {
            SupplementCard {
                if (state.scanEnabled) {
                    SupplementButton(
                        text = stringResource(R.string.consume_scan_hidden),
                        onClick = { onEvent(ConsumeUiEvent.ScanHiddenCode) },
                        enabled = !state.isSubmitting,
                        leadingIcon = SupplementIcons.Scan,
                        variant = SupplementButtonVariant.Tonal,
                        modifier = Modifier.testTag("consume_scan"),
                    )
                }
                SupplementTextField(
                    value = state.secretInput,
                    onValueChange = { onEvent(ConsumeUiEvent.SecretChanged(it)) },
                    label = stringResource(R.string.consume_secret_label),
                    leadingIcon = SupplementIcons.Secret,
                    supportingText = stringResource(R.string.consume_secret_hint),
                    monospace = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (state.canSubmit) onEvent(ConsumeUiEvent.Submit) },
                    modifier = Modifier.testTag("consume_secret"),
                )
                state.label?.let { label ->
                    InfoRow(
                        label = stringResource(R.string.consume_unit),
                        value = stringResource(R.string.consume_unit_value, label.batchId, label.index),
                        icon = SupplementIcons.Tag,
                    )
                }
                if (state.mismatch) {
                    NoticeCard(
                        message = stringResource(R.string.consume_mismatch),
                        tone = NoticeTone.Danger,
                        modifier = Modifier.testTag("consume_mismatch"),
                    )
                }
                SupplementButton(
                    text = stringResource(R.string.consume_action),
                    onClick = { onEvent(ConsumeUiEvent.Submit) },
                    enabled = state.canSubmit,
                    loading = state.isSubmitting,
                    leadingIcon = SupplementIcons.Success,
                    modifier = Modifier.testTag("consume_submit"),
                )
            }
            NoticeCard(
                title = stringResource(R.string.consume_privacy_title),
                message = stringResource(R.string.consume_privacy_body),
                tone = NoticeTone.Info,
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let {
                NoticeCard(message = it, tone = NoticeTone.Danger, modifier = Modifier.testTag("consume_error"))
            }
        }
        state.result?.let { result ->
            StatusHero(
                status = AuthenticityStatus.Consumed,
                message = stringResource(R.string.consume_done_message, result.batchId, result.index),
                modifier = Modifier.testTag("consume_done"),
            )
            SupplementCard {
                InfoRow(
                    label = stringResource(R.string.consume_result_tx),
                    value = result.txHash,
                    icon = SupplementIcons.Receipt,
                    monospace = true,
                    copyable = true,
                )
                InfoRow(
                    label = stringResource(R.string.consume_result_consumer),
                    value = result.consumer,
                    icon = SupplementIcons.Wallet,
                    monospace = true,
                    copyable = true,
                )
                InfoRow(
                    label = stringResource(R.string.consume_result_relayer),
                    value = result.submitter,
                    icon = SupplementIcons.Forward,
                    monospace = true,
                    copyable = true,
                )
                InfoRow(
                    label = stringResource(R.string.consume_result_block),
                    value = result.blockNumber.toString(),
                    icon = SupplementIcons.Time,
                )
            }
        }
    }
}
