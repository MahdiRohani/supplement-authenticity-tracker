package ir.aut.supplementtracker.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementMonoFamily
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.CopyButton
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.components.TimelineEntry
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.OwnershipEvent

private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onEvent: (HistoryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canLoad = !state.isLoading && state.productId.isNotBlank()
    SupplementScreen(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.history_title),
            subtitle = stringResource(R.string.history_subtitle),
            icon = SupplementIcons.History,
        )
        SupplementCard {
            SupplementTextField(
                value = state.productId,
                onValueChange = { onEvent(HistoryUiEvent.ProductIdChanged(it)) },
                label = stringResource(R.string.history_product_id_label),
                leadingIcon = SupplementIcons.Tag,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Search,
                onImeAction = { if (canLoad) onEvent(HistoryUiEvent.Load) },
            )
            SupplementButton(
                text = stringResource(R.string.history_load_action),
                onClick = { onEvent(HistoryUiEvent.Load) },
                enabled = canLoad,
                loading = state.isLoading,
                leadingIcon = SupplementIcons.History,
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
        state.history?.let { history ->
            SupplementCard {
                SectionHeader(
                    title = stringResource(R.string.history_unit, history.chainProductId),
                    trailing = { StatusChip(status = AuthenticityStatus.fromLifecycle(history.status)) },
                )
                InfoRow(
                    label = stringResource(R.string.history_owner),
                    value = history.currentOwner,
                    icon = SupplementIcons.Wallet,
                    monospace = true,
                    copyable = true,
                )
                Text(
                    text = stringResource(R.string.history_elapsed, history.elapsedMs.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SupplementCard {
                SectionHeader(
                    title = stringResource(R.string.history_chain_title),
                    trailing = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.history_event_count,
                                history.events.size,
                                history.events.size,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (history.events.isEmpty()) {
                    EmptyState(
                        icon = SupplementIcons.History,
                        title = stringResource(R.string.history_no_events),
                    )
                } else {
                    Column {
                        history.events.forEachIndexed { index, event ->
                            TimelineEntry(
                                isFirst = index == 0,
                                isLast = index == history.events.lastIndex,
                                highlighted = index == history.events.lastIndex,
                            ) {
                                EventContent(event)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventContent(event: OwnershipEvent) {
    val minted = event.fromAddress.equals(ZERO_ADDRESS, ignoreCase = true)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = SupplementMonoFamily)
    Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xxs)) {
        Text(
            text = stringResource(if (minted) R.string.history_event_minted else R.string.history_event_transfer),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (minted) {
                event.toAddress.shortenMiddle()
            } else {
                stringResource(
                    R.string.history_event,
                    event.fromAddress.shortenMiddle(),
                    event.toAddress.shortenMiddle(),
                )
            },
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.history_event_meta,
                    event.blockNumber,
                    event.createdAt.replace('T', ' ').take(16),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CopyButton(value = event.txHash, label = stringResource(R.string.history_tx))
        }
    }
}
