package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.VerifyResult

data class VerifyUiState(
    val input: String = "",
    val isLoading: Boolean = false,
    val result: VerifyResult? = null,
    val authenticityStatus: AuthenticityStatus? = null,
    val errorMessage: String? = null,
)

sealed interface VerifyUiEvent {
    data class InputChanged(val value: String) : VerifyUiEvent
    data object Submit : VerifyUiEvent
}

sealed interface VerifyUiEffect {
    data class ShowMessage(val message: String) : VerifyUiEffect
}
