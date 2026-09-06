package ir.aut.supplementtracker.feature.transfer

import ir.aut.supplementtracker.core.model.TransferResult

data class TransferUiState(
    val productId: String = "",
    val toAddress: String = "",
    val isSubmitting: Boolean = false,
    val result: TransferResult? = null,
    val errorMessage: String? = null,
)

sealed interface TransferUiEvent {
    data class ProductIdChanged(val value: String) : TransferUiEvent
    data class ToAddressChanged(val value: String) : TransferUiEvent
    data object Submit : TransferUiEvent
}

sealed interface TransferUiEffect {
    data class ShowMessage(val message: String) : TransferUiEffect
}
