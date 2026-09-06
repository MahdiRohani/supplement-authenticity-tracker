package ir.aut.supplementtracker.feature.history

import ir.aut.supplementtracker.core.model.OwnershipHistory

data class HistoryUiState(
    val productId: String = "",
    val isLoading: Boolean = false,
    val history: OwnershipHistory? = null,
    val errorMessage: String? = null,
)

sealed interface HistoryUiEvent {
    data class ProductIdChanged(val value: String) : HistoryUiEvent
    data object Load : HistoryUiEvent
}

sealed interface HistoryUiEffect {
    data class ShowMessage(val message: String) : HistoryUiEffect
}
