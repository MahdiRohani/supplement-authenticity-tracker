package ir.aut.supplementtracker.feature.consume

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    val canSubmit = !state.isSubmitting && state.productId.isNotBlank() && state.secret.isNotBlank()
    SupplementScreen(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.consume_title),
            subtitle = stringResource(R.string.consume_subtitle),
            icon = SupplementIcons.Consume,
        )
        NoticeCard(
            message = stringResource(R.string.consume_irreversible),
            tone = NoticeTone.Warning,
        )
        SupplementCard {
            SupplementTextField(
                value = state.productId,
                onValueChange = { onEvent(ConsumeUiEvent.ProductIdChanged(it)) },
                label = stringResource(R.string.consume_product_id_label),
                leadingIcon = SupplementIcons.Tag,
                keyboardType = KeyboardType.Number,
            )
            SupplementTextField(
                value = state.secret,
                onValueChange = { onEvent(ConsumeUiEvent.SecretChanged(it)) },
                label = stringResource(R.string.consume_secret_label),
                leadingIcon = SupplementIcons.Secret,
                supportingText = stringResource(R.string.consume_secret_hint),
                monospace = true,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = { if (canSubmit) onEvent(ConsumeUiEvent.Submit) },
            )
            SupplementButton(
                text = stringResource(R.string.consume_action),
                onClick = { onEvent(ConsumeUiEvent.Submit) },
                enabled = canSubmit,
                loading = state.isSubmitting,
                leadingIcon = SupplementIcons.Success,
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
        state.result?.let { result ->
            StatusHero(
                status = AuthenticityStatus.fromLifecycle(result.status),
                message = stringResource(R.string.consume_done_message, result.chainProductId),
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
                    label = stringResource(R.string.consume_result_actor),
                    value = result.actor,
                    icon = SupplementIcons.Pharmacy,
                    monospace = true,
                    copyable = true,
                )
            }
        }
    }
}
