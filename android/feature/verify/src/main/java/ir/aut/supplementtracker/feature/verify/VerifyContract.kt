package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.VerifyResult

data class VerifyUiState(
    val input: String = "",
    val isLoading: Boolean = false,
    val isReporting: Boolean = false,
    val result: VerifyResult? = null,
    val authenticityStatus: AuthenticityStatus? = null,
    val errorMessage: String? = null,
    val reportsEnabled: Boolean = true,
    val scanEnabled: Boolean = true,
)

sealed interface VerifyUiEvent {
    data class InputChanged(val value: String) : VerifyUiEvent
    data object Submit : VerifyUiEvent
    data object ReportCounterfeit : VerifyUiEvent
    data object ScanQr : VerifyUiEvent
}

sealed interface VerifyUiEffect {
    data class ShowMessage(val message: String) : VerifyUiEffect
    data object ReportSubmitted : VerifyUiEffect
    data object NavigateToScan : VerifyUiEffect
}
