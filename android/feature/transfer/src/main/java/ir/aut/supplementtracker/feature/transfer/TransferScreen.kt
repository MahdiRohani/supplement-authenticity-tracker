package ir.aut.supplementtracker.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.EmptyState
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.SelectableCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.components.shortenMiddle
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferResult

/** A known supply-chain party offered as a one-tap recipient. */
data class RecipientSuggestion(
    val label: String,
    val address: String,
    val icon: ImageVector? = null,
)

@Composable
fun TransferScreen(
    state: TransferUiState,
    onEvent: (TransferUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    recipientSuggestions: List<RecipientSuggestion> = emptyList(),
) {
    SupplementScreen(modifier = modifier.testTag("transfer_screen")) {
        ScreenHeader(
            title = stringResource(R.string.transfer_title),
            subtitle = stringResource(R.string.transfer_subtitle),
            icon = SupplementIcons.Transfer,
            trailing = {
                IconButton(
                    onClick = { onEvent(TransferUiEvent.Refresh) },
                    enabled = !state.isLoadingSegments && !state.isSubmitting,
                ) {
                    Icon(SupplementIcons.Refresh, contentDescription = stringResource(R.string.transfer_refresh))
                }
            },
        )

        state.result?.let { TransferDone(it) }

        SectionHeader(title = stringResource(R.string.transfer_pick_segment))
        if (state.isLoadingSegments) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.segments.isEmpty() && !state.isLoadingSegments) {
            EmptyState(
                icon = SupplementIcons.Stock,
                title = stringResource(R.string.transfer_no_segments),
                message = stringResource(R.string.transfer_no_segments_hint),
            )
        }
        state.segments.forEach { segment ->
            SegmentOption(
                segment = segment,
                selected = segment.segmentId == state.selectedSegmentId,
                onClick = { onEvent(TransferUiEvent.SegmentSelected(segment.segmentId)) },
            )
        }

        state.selected?.let { segment ->
            TransferForm(
                state = state,
                segment = segment,
                onEvent = onEvent,
                recipientSuggestions = recipientSuggestions,
            )
        }

        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
    }
}

@Composable
private fun SegmentOption(
    segment: Segment,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = segment.batchName ?: stringResource(R.string.transfer_batch_fallback, segment.batchId)
    val units = pluralStringResource(R.plurals.transfer_unit_count, segment.units, segment.units)
    SelectableCard(
        selected = selected,
        title = title,
        description = stringResource(
            R.string.transfer_segment_desc,
            segment.lotCode ?: "—",
            segment.start,
            segment.lastIndex,
            units,
        ),
        icon = SupplementIcons.Stock,
        onClick = onClick,
        modifier = Modifier.testTag("transfer_segment_${segment.segmentId}"),
    )
}

@Composable
private fun TransferForm(
    state: TransferUiState,
    segment: Segment,
    onEvent: (TransferUiEvent) -> Unit,
    recipientSuggestions: List<RecipientSuggestion>,
) {
    SupplementCard {
        SupplementTextField(
            value = state.count,
            onValueChange = { onEvent(TransferUiEvent.CountChanged(it)) },
            label = stringResource(R.string.transfer_count_label),
            leadingIcon = SupplementIcons.Tag,
            supportingText = stringResource(R.string.transfer_count_hint, segment.units),
            isError = state.count.isNotEmpty() && !state.countValid,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.testTag("transfer_count"),
        )
        if (state.isPartial) {
            val moving = state.countValue ?: 0
            Text(
                text = stringResource(
                    R.string.transfer_split_preview,
                    segment.start,
                    segment.start + moving - 1,
                    segment.start + moving,
                    segment.lastIndex,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SupplementTextField(
            value = state.toAddress,
            onValueChange = { onEvent(TransferUiEvent.ToAddressChanged(it)) },
            label = stringResource(R.string.recipient_label),
            leadingIcon = SupplementIcons.Wallet,
            monospace = true,
            isError = state.toAddress.isNotEmpty() && !state.addressValid,
            supportingText = if (state.toAddress.isNotEmpty() && !state.addressValid) {
                stringResource(R.string.recipient_invalid)
            } else {
                null
            },
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canSubmit) onEvent(TransferUiEvent.Submit) },
            modifier = Modifier.testTag("transfer_to"),
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
            text = state.countValue?.takeIf { state.countValid }?.let {
                pluralStringResource(R.plurals.transfer_action_count, it, it)
            } ?: stringResource(R.string.transfer_action),
            onClick = { onEvent(TransferUiEvent.Submit) },
            enabled = state.canSubmit,
            loading = state.isSubmitting,
            leadingIcon = SupplementIcons.Forward,
            modifier = Modifier.testTag("transfer_submit"),
        )
    }
}

@Composable
private fun TransferDone(result: SegmentTransferResult) {
    val units = result.units.toInt()
    NoticeCard(
        title = stringResource(R.string.transfer_done_title),
        message = stringResource(
            if (result.split) R.string.transfer_done_split else R.string.transfer_done_message,
            pluralStringResource(R.plurals.transfer_unit_count, units, units),
            result.batchId,
        ),
        tone = NoticeTone.Success,
        modifier = Modifier.testTag("transfer_done"),
    )
    SupplementCard {
        InfoRow(
            label = stringResource(R.string.transfer_to),
            value = result.to,
            icon = SupplementIcons.Forward,
            monospace = true,
            copyable = true,
        )
        InfoRow(
            label = stringResource(R.string.transfer_range),
            value = stringResource(R.string.transfer_range_value, result.start, result.end - 1),
            icon = SupplementIcons.Tag,
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
