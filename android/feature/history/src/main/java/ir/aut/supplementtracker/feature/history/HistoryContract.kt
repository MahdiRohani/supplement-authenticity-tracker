package ir.aut.supplementtracker.feature.history

import ir.aut.supplementtracker.core.model.OwnershipHistory
import ir.aut.supplementtracker.core.model.UnitHistory

data class HistoryUiState(
    /** A unit code (`chain/batch/index` or verify URL), or a legacy v1 product ID. */
    val input: String = "",
    val isLoading: Boolean = false,
    val history: OwnershipHistory? = null,
    val unitHistory: UnitHistory? = null,
    val errorMessage: String? = null,
)

sealed interface HistoryUiEvent {
    data class InputChanged(val value: String) : HistoryUiEvent
    data object Load : HistoryUiEvent
}

sealed interface HistoryUiEffect {
    data class ShowMessage(val message: String) : HistoryUiEffect
    data class Loaded(val elapsedMs: Long) : HistoryUiEffect
}
