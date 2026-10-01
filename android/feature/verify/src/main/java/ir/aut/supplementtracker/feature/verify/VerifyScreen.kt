package ir.aut.supplementtracker.feature.verify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.HeroBanner
import ir.aut.supplementtracker.core.designsystem.components.InfoRow
import ir.aut.supplementtracker.core.designsystem.components.NoticeCard
import ir.aut.supplementtracker.core.designsystem.components.NoticeTone
import ir.aut.supplementtracker.core.designsystem.components.SectionHeader
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.StatusHero
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementButtonVariant
import ir.aut.supplementtracker.core.designsystem.components.SupplementCard
import ir.aut.supplementtracker.core.designsystem.components.SupplementScreen
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import ir.aut.supplementtracker.core.designsystem.localizedErrorMessage
import ir.aut.supplementtracker.core.model.VerifyResult

@Composable
fun VerifyScreen(
    state: VerifyUiState,
    onEvent: (VerifyUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = !state.isLoading && state.input.isNotBlank()
    SupplementScreen(modifier = modifier.testTag("verify_screen")) {
        HeroBanner(
            title = stringResource(R.string.verify_title),
            subtitle = stringResource(R.string.verify_guest_hint),
            icon = SupplementIcons.Verify,
        )

        SupplementCard {
            SupplementTextField(
                value = state.input,
                onValueChange = { onEvent(VerifyUiEvent.InputChanged(it)) },
                label = stringResource(R.string.verify_input_label),
                leadingIcon = SupplementIcons.Tag,
                supportingText = stringResource(R.string.verify_input_support),
                imeAction = ImeAction.Search,
                onImeAction = { if (canSubmit) onEvent(VerifyUiEvent.Submit) },
                modifier = Modifier.testTag("verify_input"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm)) {
                SupplementButton(
                    text = stringResource(R.string.verify_action),
                    onClick = { onEvent(VerifyUiEvent.Submit) },
                    enabled = canSubmit,
                    loading = state.isLoading,
                    leadingIcon = SupplementIcons.Search,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("verify_submit"),
                )
                if (state.scanEnabled) {
                    SupplementButton(
                        text = stringResource(R.string.verify_scan_qr),
                        onClick = { onEvent(VerifyUiEvent.ScanQr) },
                        enabled = !state.isLoading,
                        variant = SupplementButtonVariant.Tonal,
                        leadingIcon = SupplementIcons.Scan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_scan"),
                    )
                }
            }
        }

        state.authenticityStatus?.let { status ->
            StatusHero(
                status = status,
                modifier = Modifier.testTag("verify_status_${status.name}"),
            )
        }

        state.errorMessage?.let { raw ->
            localizedErrorMessage(raw)?.let {
                NoticeCard(
                    message = it,
                    tone = NoticeTone.Danger,
                    modifier = Modifier.testTag("verify_error"),
                )
            }
        }

        AnimatedVisibility(
            visible = state.result != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.result?.let { result ->
                ResultDetails(
                    result = result,
                    status = state.authenticityStatus,
                    reportsEnabled = state.reportsEnabled,
                    reportEnabled = !state.isReporting && !state.isLoading,
                    isReporting = state.isReporting,
                    onReport = { onEvent(VerifyUiEvent.ReportCounterfeit) },
                )
            }
        }

        if (state.result == null && state.authenticityStatus == null && !state.isLoading) {
            HowItWorks()
        }
    }
}

@Composable
private fun ResultDetails(
    result: VerifyResult,
    status: AuthenticityStatus?,
    reportsEnabled: Boolean,
    reportEnabled: Boolean,
    isReporting: Boolean,
    onReport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Md)) {
        SupplementCard {
            SectionHeader(
                title = result.metadata?.name ?: stringResource(R.string.verify_details_title),
                trailing = {
                    StatusChip(status = AuthenticityStatus.fromLifecycle(result.status))
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            result.metadata?.name?.let {
                InfoRow(label = stringResource(R.string.verify_name), value = it, icon = SupplementIcons.Consume)
            }
            result.metadata?.batch?.let {
                InfoRow(label = stringResource(R.string.verify_batch), value = it, icon = SupplementIcons.Receipt)
            }
            result.metadata?.expiresAt?.let {
                InfoRow(label = stringResource(R.string.verify_expires), value = it, icon = SupplementIcons.Time)
            }
            InfoRow(
                label = stringResource(R.string.verify_product_id),
                value = "#${result.chainProductId}",
                icon = SupplementIcons.Tag,
            )
            InfoRow(
                label = stringResource(R.string.verify_owner),
                value = result.currentOwner,
                icon = SupplementIcons.Wallet,
                monospace = true,
                copyable = true,
            )
            result.metadataGatewayUrl?.let {
                InfoRow(
                    label = stringResource(R.string.verify_metadata_link),
                    value = it,
                    icon = SupplementIcons.Link,
                    copyable = true,
                )
            }
            Text(
                text = stringResource(R.string.verify_source, result.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (reportsEnabled) {
            val suspicious = status == AuthenticityStatus.Consumed || status == AuthenticityStatus.Invalid
            NoticeCard(
                title = stringResource(R.string.verify_report_title),
                message = stringResource(R.string.verify_report_hint),
                tone = if (suspicious) NoticeTone.Warning else NoticeTone.Info,
                icon = SupplementIcons.Report,
            ) {
                SupplementButton(
                    text = stringResource(R.string.verify_report_counterfeit),
                    onClick = onReport,
                    enabled = reportEnabled,
                    loading = isReporting,
                    variant = SupplementButtonVariant.Danger,
                    leadingIcon = SupplementIcons.Report,
                    modifier = Modifier.testTag("verify_report"),
                )
            }
        }
    }
}

@Composable
private fun HowItWorks() {
    SupplementCard {
        SectionHeader(title = stringResource(R.string.verify_how_title))
        listOf(
            SupplementIcons.Scan to R.string.verify_how_step1,
            SupplementIcons.Verified to R.string.verify_how_step2,
            SupplementIcons.Report to R.string.verify_how_step3,
        ).forEachIndexed { index, (icon, text) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "${index + 1}", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(
                    text = stringResource(text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun VerifyStatusPreview(status: AuthenticityStatus) {
    StatusChip(status = status, modifier = Modifier.testTag("preview_${status.name}"))
}
