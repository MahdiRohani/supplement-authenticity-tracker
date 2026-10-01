package ir.aut.supplementtracker.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage

/** A known supply-chain party offered as a one-tap recipient. */
data class RecipientSuggestion(
    val label: String,
    val address: String,
    val icon: ImageVector? = null,
)

private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

@Composable
fun TransferScreen(
    state: TransferUiState,
    onEvent: (TransferUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    recipientSuggestions: List<RecipientSuggestion> = emptyList(),
) {
    val addressValid = EVM_ADDRESS.matches(state.toAddress.trim())
    val canSubmit = !state.isSubmitting && state.productId.isNotBlank() && addressValid
    SupplementScreen(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.transfer_title),
            subtitle = stringResource(R.string.transfer_subtitle),
            icon = SupplementIcons.Transfer,
        )
        SupplementCard {
            SupplementTextField(
                value = state.productId,
                onValueChange = { onEvent(TransferUiEvent.ProductIdChanged(it)) },
                label = stringResource(R.string.product_id_label),
                leadingIcon = SupplementIcons.Tag,
                keyboardType = KeyboardType.Number,
            )
            SupplementTextField(
                value = state.toAddress,
                onValueChange = { onEvent(TransferUiEvent.ToAddressChanged(it)) },
                label = stringResource(R.string.recipient_label),
                leadingIcon = SupplementIcons.Wallet,
                monospace = true,
                isError = state.toAddress.isNotEmpty() && !addressValid,
                supportingText = if (state.toAddress.isNotEmpty() && !addressValid) {
                    stringResource(R.string.recipient_invalid)
                } else {
                    null
                },
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onImeAction = { if (canSubmit) onEvent(TransferUiEvent.Submit) },
            )
            if (recipientSuggestions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recipient_quick_pick),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs)) {
                    items(recipientSuggestions, key = { it.label + it.address }) { suggestion ->
                        FilterChip(
                            selected = state.toAddress.equals(suggestion.address, ignoreCase = true),
                            onClick = { onEvent(TransferUiEvent.ToAddressChanged(suggestion.address)) },
                            label = { Text("${suggestion.label} · ${suggestion.address.shortenMiddle()}") },
                            leadingIcon = suggestion.icon?.let { icon ->
                                { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
            SupplementButton(
                text = stringResource(R.string.transfer_action),
                onClick = { onEvent(TransferUiEvent.Submit) },
                enabled = canSubmit,
                loading = state.isSubmitting,
                leadingIcon = SupplementIcons.Forward,
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
        state.result?.let { result ->
            NoticeCard(
                title = stringResource(R.string.transfer_done_title),
                message = stringResource(R.string.transfer_done_message, result.chainProductId),
                tone = NoticeTone.Success,
            )
            SupplementCard {
                InfoRow(
                    label = stringResource(R.string.transfer_from),
                    value = result.fromAddress,
                    icon = SupplementIcons.Wallet,
                    monospace = true,
                    copyable = true,
                )
                InfoRow(
                    label = stringResource(R.string.transfer_to),
                    value = result.toAddress,
                    icon = SupplementIcons.Forward,
                    monospace = true,
                    copyable = true,
                )
                InfoRow(
                    label = stringResource(R.string.transfer_result),
                    value = result.txHash,
                    icon = SupplementIcons.Receipt,
                    monospace = true,
                    copyable = true,
                )
            }
        }
    }
}
