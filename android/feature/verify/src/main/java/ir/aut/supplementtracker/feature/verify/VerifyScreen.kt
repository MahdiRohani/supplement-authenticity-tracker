package ir.aut.supplementtracker.feature.verify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ir.aut.supplementtracker.core.designsystem.SupplementIcons
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
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
import ir.aut.supplementtracker.core.model.IndependentCheck
import ir.aut.supplementtracker.core.model.RiskAssessment
import ir.aut.supplementtracker.core.model.RiskReasonCode
import ir.aut.supplementtracker.core.model.UnitVerification
import ir.aut.supplementtracker.core.model.VerifiedUnit
import ir.aut.supplementtracker.core.model.VerifyResult
import kotlin.math.roundToInt

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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RegionPicker(
                region = state.region,
                enabled = !state.isLoading,
                onSelected = { onEvent(VerifyUiEvent.RegionSelected(it)) },
            )
        }

        if (state.hiddenLabelEntered) {
            NoticeCard(
                title = stringResource(R.string.verify_hidden_entered_title),
                message = stringResource(R.string.verify_hidden_entered_body),
                tone = NoticeTone.Warning,
                icon = SupplementIcons.Secret,
                modifier = Modifier.testTag("verify_hidden_warning"),
            )
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
            visible = state.unit != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.unit?.let { unit ->
                UnitDetails(
                    unit = unit,
                    canRecordConsumption = state.canRecordConsumption,
                    onRecordConsumption = { onEvent(VerifyUiEvent.RecordConsumption) },
                )
            }
        }

        AnimatedVisibility(
            visible = state.result != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            state.result?.let { result -> ResultDetails(result = result) }
        }

        if (state.hasResult && state.reportsEnabled) {
            ReportCard(
                status = state.authenticityStatus,
                enabled = !state.isReporting && !state.isLoading,
                isReporting = state.isReporting,
                onReport = { onEvent(VerifyUiEvent.ReportCounterfeit) },
            )
        }

        if (!state.hasResult && state.authenticityStatus == null && !state.isLoading) {
            HowItWorks()
        }
    }
}

@Composable
private fun isPersian(): Boolean = LocalConfiguration.current.locales[0].language == "fa"

@Composable
private fun RegionPicker(
    region: String?,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
) {
    val persian = isPersian()
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.verify_region_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Box {
                SupplementButton(
                    text = regionLabel(region, persian) ?: stringResource(R.string.verify_region_none),
                    onClick = { expanded = true },
                    enabled = enabled,
                    variant = SupplementButtonVariant.Outlined,
                    fillWidth = false,
                    modifier = Modifier.testTag("verify_region"),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.verify_region_none)) },
                        onClick = {
                            expanded = false
                            onSelected(null)
                        },
                    )
                    ScanRegions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label(persian)) },
                            onClick = {
                                expanded = false
                                onSelected(option.slug)
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.verify_region_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnitDetails(
    unit: VerifiedUnit,
    canRecordConsumption: Boolean,
    onRecordConsumption: () -> Unit,
) {
    val v = unit.verification
    Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Md)) {
        if (unit.check.contradicted) {
            NoticeCard(
                title = stringResource(R.string.verify_contradicted_title),
                message = stringResource(R.string.verify_contradicted_body),
                tone = NoticeTone.Danger,
                modifier = Modifier.testTag("verify_contradicted"),
            )
        }
        ProductCard(v)
        RiskCard(risk = v.risk, scanCount = v.scanCount)
        if (canRecordConsumption) {
            NoticeCard(
                title = stringResource(R.string.verify_consume_cta_title),
                message = stringResource(R.string.verify_consume_cta_body),
                tone = NoticeTone.Success,
                icon = SupplementIcons.Consume,
            ) {
                SupplementButton(
                    text = stringResource(R.string.verify_consume_cta),
                    onClick = onRecordConsumption,
                    leadingIcon = SupplementIcons.Secret,
                    modifier = Modifier.testTag("verify_consume"),
                )
            }
        }
        v.consumption?.let { consumption ->
            SupplementCard {
                SectionHeader(title = stringResource(R.string.verify_consumed_title))
                consumption.consumer?.let {
                    InfoRow(
                        label = stringResource(R.string.verify_consumer),
                        value = it,
                        icon = SupplementIcons.Wallet,
                        monospace = true,
                        copyable = true,
                    )
                }
                InfoRow(
                    label = stringResource(R.string.verify_consume_tx),
                    value = consumption.txHash,
                    icon = SupplementIcons.Receipt,
                    monospace = true,
                    copyable = true,
                )
                consumption.at?.let {
                    InfoRow(label = stringResource(R.string.verify_consumed_at), value = it, icon = SupplementIcons.Time)
                }
            }
        }
        EvidenceCard(v, unit.check)
    }
}

@Composable
private fun ProductCard(v: UnitVerification) {
    val persian = isPersian()
    SupplementCard {
        SectionHeader(
            title = v.productName ?: stringResource(R.string.verify_details_title),
            trailing = {
                v.segmentStatus?.let { StatusChip(status = AuthenticityStatus.fromLifecycle(it)) }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InfoRow(
            label = stringResource(R.string.verify_unit),
            value = stringResource(R.string.verify_unit_value, v.unit.batchId, v.unit.index),
            icon = SupplementIcons.Tag,
        )
        v.lotCode?.let {
            InfoRow(label = stringResource(R.string.verify_lot), value = it, icon = SupplementIcons.Receipt)
        }
        v.manufacturer?.let { party ->
            InfoRow(
                label = stringResource(R.string.verify_manufacturer),
                value = party.displayName ?: party.address,
                icon = SupplementIcons.Manufacturer,
                monospace = party.displayName == null,
                copyable = party.displayName == null,
            )
        }
        v.custodian?.let { party ->
            val name = party.displayName ?: party.address
            val region = regionLabel(party.region, persian)
            InfoRow(
                label = stringResource(R.string.verify_custodian),
                value = region?.let { stringResource(R.string.verify_custodian_region, name, it) } ?: name,
                icon = SupplementIcons.Pharmacy,
                monospace = party.displayName == null,
            )
        }
        v.metadataGatewayUrl?.takeIf { it.isNotBlank() }?.let {
            InfoRow(
                label = stringResource(R.string.verify_metadata_link),
                value = it,
                icon = SupplementIcons.Link,
                copyable = true,
            )
        }
    }
}

@Composable
private fun RiskCard(risk: RiskAssessment, scanCount: Int) {
    val tone = riskLevelOf(risk.level)
    val levelText = stringResource(
        when (tone) {
            RiskTone.Low -> R.string.verify_risk_low
            RiskTone.Medium -> R.string.verify_risk_medium
            RiskTone.High -> R.string.verify_risk_high
        },
    )
    val reasons = risk.reasons.map { reason -> riskReasonText(reason.code) ?: reason.message }
    NoticeCard(
        title = "${stringResource(R.string.verify_risk_title)}: $levelText",
        message = if (reasons.isEmpty()) stringResource(R.string.verify_risk_none) else reasons.joinToString("\n") { "• $it" },
        tone = when (tone) {
            RiskTone.Low -> NoticeTone.Success
            RiskTone.Medium -> NoticeTone.Warning
            RiskTone.High -> NoticeTone.Danger
        },
        icon = if (tone == RiskTone.Low) SupplementIcons.Verified else SupplementIcons.Warning,
        modifier = Modifier.testTag("verify_risk_${risk.level}"),
    ) {
        val percent = (risk.score * 100).roundToInt().coerceIn(0, 100)
        Column(verticalArrangement = Arrangement.spacedBy(SupplementSpacing.Xs)) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.verify_risk_score, percent) + " · " +
                    stringResource(R.string.verify_scan_count, scanCount),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun riskReasonText(code: String): String? =
    when (code) {
        RiskReasonCode.MANY_DEVICES -> stringResource(R.string.risk_reason_many_devices)
        RiskReasonCode.SCANNED_AFTER_CONSUMPTION -> stringResource(R.string.risk_reason_scanned_after_consumption)
        RiskReasonCode.FOREIGN_REGION -> stringResource(R.string.risk_reason_foreign_region)
        RiskReasonCode.REGION_SPREAD -> stringResource(R.string.risk_reason_region_spread)
        else -> null
    }

@Composable
private fun EvidenceCard(v: UnitVerification, check: IndependentCheck) {
    SupplementCard(modifier = Modifier.testTag("verify_evidence")) {
        SectionHeader(title = stringResource(R.string.verify_evidence_title))
        CheckRow(stringResource(R.string.verify_check_proof), check.proofValid, "verify_check_proof")
        CheckRow(stringResource(R.string.verify_check_root), check.rootMatchesChain, "verify_check_root")
        CheckRow(stringResource(R.string.verify_check_consumed), check.consumedMatchesChain, "verify_check_consumed")
        v.evidence?.let { evidence ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow(
                label = stringResource(R.string.verify_merkle_root),
                value = evidence.merkleRoot,
                monospace = true,
                copyable = true,
            )
            InfoRow(
                label = stringResource(R.string.verify_unit_key),
                value = evidence.unitKey,
                monospace = true,
                copyable = true,
            )
            InfoRow(
                label = stringResource(R.string.verify_proof_length),
                value = evidence.proof.size.toString(),
            )
            if (evidence.registerTxHash.isNotBlank()) {
                InfoRow(
                    label = stringResource(R.string.verify_register_tx),
                    value = evidence.registerTxHash,
                    monospace = true,
                    copyable = true,
                )
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, passed: Boolean?, tag: String) {
    val colors = SupplementTheme.statusColors
    val (icon: ImageVector, text, tint) =
        when (passed) {
            true -> Triple(SupplementIcons.Success, stringResource(R.string.verify_check_pass), colors.success.accent)
            false -> Triple(SupplementIcons.Error, stringResource(R.string.verify_check_fail), colors.danger.accent)
            null -> Triple(
                SupplementIcons.Unknown,
                stringResource(R.string.verify_check_skipped),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${tag}_${passed ?: "skipped"}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SupplementSpacing.Sm),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun ResultDetails(result: VerifyResult) {
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
}

@Composable
private fun ReportCard(
    status: AuthenticityStatus?,
    enabled: Boolean,
    isReporting: Boolean,
    onReport: () -> Unit,
) {
    val suspicious = status == AuthenticityStatus.Consumed ||
        status == AuthenticityStatus.Invalid ||
        status == AuthenticityStatus.Suspicious ||
        status == AuthenticityStatus.Recalled
    NoticeCard(
        title = stringResource(R.string.verify_report_title),
        message = stringResource(R.string.verify_report_hint),
        tone = if (suspicious) NoticeTone.Warning else NoticeTone.Info,
        icon = SupplementIcons.Report,
    ) {
        SupplementButton(
            text = stringResource(R.string.verify_report_counterfeit),
            onClick = onReport,
            enabled = enabled,
            loading = isReporting,
            variant = SupplementButtonVariant.Danger,
            leadingIcon = SupplementIcons.Report,
            modifier = Modifier.testTag("verify_report"),
        )
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
