package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.VerifiedUnit
import ir.aut.supplementtracker.core.model.VerifyResult

data class VerifyUiState(
    val input: String = "",
    val isLoading: Boolean = false,
    val isReporting: Boolean = false,
    /** v1 single-product result (legacy labels). */
    val result: VerifyResult? = null,
    /** v2 unit result with risk and on-device evidence checks. */
    val unit: VerifiedUnit? = null,
    val authenticityStatus: AuthenticityStatus? = null,
    val errorMessage: String? = null,
    val reportsEnabled: Boolean = true,
    val scanEnabled: Boolean = true,
    /** Province slug sent as `X-Scan-Region`; null when the buyer prefers not to say. */
    val region: String? = null,
    /** The scratch-off code was entered here; it must stay private. */
    val hiddenLabelEntered: Boolean = false,
) {
    val hasResult: Boolean get() = result != null || unit != null

    /** Only a sellable, uncontested unit can be consumed by its buyer. */
    val canRecordConsumption: Boolean
        get() = unit?.let {
            it.verification.authenticity == "Authentic" && !it.verification.consumed && !it.check.contradicted
        } ?: false
}

sealed interface VerifyUiEvent {
    data class InputChanged(val value: String) : VerifyUiEvent
    data object Submit : VerifyUiEvent
    data object ReportCounterfeit : VerifyUiEvent
    data object ScanQr : VerifyUiEvent
    data class RegionSelected(val region: String?) : VerifyUiEvent
    data object RecordConsumption : VerifyUiEvent
}

sealed interface VerifyUiEffect {
    data class ShowMessage(val message: String) : VerifyUiEffect
    data object ReportSubmitted : VerifyUiEffect
    data object NavigateToScan : VerifyUiEffect
    data class NavigateToConsume(val unit: UnitRef) : VerifyUiEffect
}
