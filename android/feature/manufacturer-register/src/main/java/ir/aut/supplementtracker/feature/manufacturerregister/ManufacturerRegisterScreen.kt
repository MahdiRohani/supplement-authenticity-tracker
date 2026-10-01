package ir.aut.supplementtracker.feature.manufacturerregister

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementMonoFamily
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.CopyButton
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.ScreenHeader
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage

@Composable
fun ManufacturerRegisterScreen(
    state: ManufacturerRegisterUiState,
    onEvent: (ManufacturerRegisterUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = !state.isSubmitting && state.name.isNotBlank()
    SupplementScreen(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.manufacturer_register_title),
            subtitle = stringResource(R.string.manufacturer_register_subtitle),
            icon = SupplementIcons.Register,
        )
        SupplementCard {
            SupplementTextField(
                value = state.name,
                onValueChange = { onEvent(ManufacturerRegisterUiEvent.NameChanged(it)) },
                label = stringResource(R.string.product_name_label),
                leadingIcon = SupplementIcons.Consume,
            )
            SupplementTextField(
                value = state.batch,
                onValueChange = { onEvent(ManufacturerRegisterUiEvent.BatchChanged(it)) },
                label = stringResource(R.string.batch_label),
                leadingIcon = SupplementIcons.Receipt,
                imeAction = ImeAction.Done,
                onImeAction = { if (canSubmit) onEvent(ManufacturerRegisterUiEvent.Submit) },
            )
            SupplementButton(
                text = stringResource(R.string.register_action),
                onClick = { onEvent(ManufacturerRegisterUiEvent.Submit) },
                enabled = canSubmit,
                loading = state.isSubmitting,
                leadingIcon = SupplementIcons.Register,
            )
        }
        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let { NoticeCard(message = it, tone = NoticeTone.Danger) }
        }
        state.result?.let { product ->
            NoticeCard(
                title = stringResource(R.string.register_done_title),
                message = stringResource(R.string.register_done_message, product.chainProductId),
                tone = NoticeTone.Success,
            )
            SupplementCard {
                SectionHeader(
                    title = state.name.ifBlank { stringResource(R.string.register_unit) },
                    trailing = { StatusChip(status = AuthenticityStatus.fromLifecycle(product.status)) },
                )
                InfoRow(
                    label = stringResource(R.string.result_id),
                    value = "#${product.chainProductId}",
                    icon = SupplementIcons.Tag,
                    copyable = true,
                )
                product.metadataCid?.let { cid ->
                    InfoRow(
                        label = stringResource(R.string.result_cid),
                        value = cid,
                        icon = SupplementIcons.Link,
                        monospace = true,
                        copyable = true,
                    )
                }
            }
            product.secret?.let { secret ->
                NoticeCard(
                    title = stringResource(R.string.result_secret),
                    message = stringResource(R.string.result_secret_warning),
                    tone = NoticeTone.Warning,
                    icon = SupplementIcons.Secret,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = secret,
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SupplementMonoFamily),
                            )
                        }
                        CopyButton(value = secret, label = stringResource(R.string.result_secret))
                    }
                    SupplementButton(
                        text = stringResource(R.string.hide_secret_action),
                        onClick = { onEvent(ManufacturerRegisterUiEvent.ClearResult) },
                        variant = SupplementButtonVariant.Outlined,
                    )
                }
            }
        }
    }
}
