package ir.aut.supplementtracker.feature.manufacturerregister

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.RegisteredBatch

@Composable
fun ManufacturerRegisterScreen(
    state: ManufacturerRegisterUiState,
    onEvent: (ManufacturerRegisterUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SupplementScreen(modifier = modifier.testTag("register_screen")) {
        ScreenHeader(
            title = stringResource(R.string.manufacturer_register_title),
            subtitle = stringResource(R.string.manufacturer_register_subtitle),
            icon = SupplementIcons.Register,
        )
        val result = state.result
        if (result == null) {
            BatchForm(state = state, onEvent = onEvent)
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
        if (result != null) {
            RegisteredBatchCard(result)
            LabelsCard(state = state, batch = result, onEvent = onEvent)
        }
    }
}

@Composable
private fun BatchForm(
    state: ManufacturerRegisterUiState,
    onEvent: (ManufacturerRegisterUiEvent) -> Unit,
) {
    SupplementCard {
        SupplementTextField(
            value = state.name,
            onValueChange = { onEvent(ManufacturerRegisterUiEvent.NameChanged(it)) },
            label = stringResource(R.string.product_name_label),
            leadingIcon = SupplementIcons.Consume,
            modifier = Modifier.testTag("register_name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
            SupplementTextField(
                value = state.lotCode,
                onValueChange = { onEvent(ManufacturerRegisterUiEvent.LotCodeChanged(it)) },
                label = stringResource(R.string.batch_label),
                leadingIcon = SupplementIcons.Receipt,
                modifier = Modifier
                    .weight(1f)
                    .testTag("register_lot"),
            )
            SupplementTextField(
                value = state.size,
                onValueChange = { onEvent(ManufacturerRegisterUiEvent.SizeChanged(it)) },
                label = stringResource(R.string.register_size_label),
                keyboardType = KeyboardType.Number,
                isError = state.size.isNotEmpty() && !state.sizeValid,
                modifier = Modifier
                    .width(120.dp)
                    .testTag("register_size"),
            )
        }
        SupplementTextField(
            value = state.expiresAt,
            onValueChange = { onEvent(ManufacturerRegisterUiEvent.ExpiresAtChanged(it)) },
            label = stringResource(R.string.register_expires_label),
            leadingIcon = SupplementIcons.Time,
            supportingText = stringResource(R.string.register_expires_hint),
            isError = !state.expiresValid,
            imeAction = ImeAction.Done,
            onImeAction = { if (state.canSubmit) onEvent(ManufacturerRegisterUiEvent.Submit) },
        )
        Text(
            text = stringResource(R.string.register_size_hint, state.maxSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SupplementButton(
            text = stringResource(R.string.register_action),
            onClick = { onEvent(ManufacturerRegisterUiEvent.Submit) },
            enabled = state.canSubmit,
            loading = state.isSubmitting,
            leadingIcon = SupplementIcons.Register,
            modifier = Modifier.testTag("register_submit"),
        )
    }
    NoticeCard(
        title = stringResource(R.string.register_how_title),
        message = stringResource(R.string.register_how_body),
        tone = NoticeTone.Info,
    )
}

@Composable
private fun RegisteredBatchCard(result: RegisteredBatch) {
    val batch = result.batch
    NoticeCard(
        title = stringResource(R.string.register_done_title),
        message = stringResource(R.string.register_done_message, batch.size, batch.batchId),
        tone = NoticeTone.Success,
        modifier = Modifier.testTag("register_done"),
    )
    SupplementCard {
        SectionHeader(title = batch.name ?: stringResource(R.string.register_unit))
        InfoRow(
            label = stringResource(R.string.result_id),
            value = "#${batch.batchId}",
            icon = SupplementIcons.Tag,
            copyable = true,
        )
        batch.lotCode?.let {
            InfoRow(label = stringResource(R.string.batch_label), value = it, icon = SupplementIcons.Receipt)
        }
        InfoRow(
            label = stringResource(R.string.result_root),
            value = batch.merkleRoot,
            icon = SupplementIcons.Verified,
            monospace = true,
            copyable = true,
        )
        if (batch.metadataCid.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.result_cid),
                value = batch.metadataCid,
                icon = SupplementIcons.Link,
                monospace = true,
                copyable = true,
            )
        }
        if (batch.txHash.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.result_tx),
                value = batch.txHash,
                icon = SupplementIcons.Receipt,
                monospace = true,
                copyable = true,
            )
        }
        if (!result.ipfsPinned) {
            Text(
                text = stringResource(R.string.result_not_pinned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabelsCard(
    state: ManufacturerRegisterUiState,
    batch: RegisteredBatch,
    onEvent: (ManufacturerRegisterUiEvent) -> Unit,
) {
    if (state.keysHeld) {
        NoticeCard(
            title = stringResource(R.string.result_secret),
            message = stringResource(R.string.result_secret_warning, batch.units.size),
            tone = NoticeTone.Warning,
            icon = SupplementIcons.Secret,
            modifier = Modifier.testTag("register_keys_held"),
        ) {
            if (state.labelsPdfEnabled) {
                SupplementButton(
                    text = stringResource(R.string.export_labels_action),
                    onClick = { onEvent(ManufacturerRegisterUiEvent.ExportLabels) },
                    enabled = !state.isExporting,
                    loading = state.isExporting,
                    leadingIcon = SupplementIcons.Pdf,
                    modifier = Modifier.testTag("register_export"),
                )
            }
            SupplementButton(
                text = stringResource(R.string.hide_secret_action),
                onClick = { onEvent(ManufacturerRegisterUiEvent.DiscardKeys) },
                enabled = state.labelsExported || !state.labelsPdfEnabled,
                variant = SupplementButtonVariant.Outlined,
                modifier = Modifier.testTag("register_discard"),
            )
        }
    } else {
        NoticeCard(
            message = stringResource(R.string.keys_discarded),
            tone = NoticeTone.Info,
            icon = SupplementIcons.Secret,
            modifier = Modifier.testTag("register_keys_discarded"),
        ) {
            SupplementButton(
                text = stringResource(R.string.register_another),
                onClick = { onEvent(ManufacturerRegisterUiEvent.StartOver) },
                variant = SupplementButtonVariant.Tonal,
                leadingIcon = SupplementIcons.Register,
            )
        }
    }
}
