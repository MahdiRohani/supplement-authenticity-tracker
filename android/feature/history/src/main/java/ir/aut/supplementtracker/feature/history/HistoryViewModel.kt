package ir.aut.supplementtracker.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val getHistory: GetOwnershipHistoryUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HistoryUiEffect>()
    val effects: SharedFlow<HistoryUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: HistoryUiEvent) {
        when (event) {
            is HistoryUiEvent.ProductIdChanged ->
                _state.update { it.copy(productId = event.value, errorMessage = null) }
            HistoryUiEvent.Load -> load()
        }
    }

    private fun load() {
        val current = _state.value
        if (current.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { getHistory(current.productId.trim()) }
                .onSuccess { history ->
                    _state.update { it.copy(isLoading = false, history = history) }
                    _effects.emit(
                        HistoryUiEffect.ShowMessage("Loaded in ${history.elapsedMs} ms"),
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "History failed",
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(getHistory: GetOwnershipHistoryUseCase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(getHistory) as T
                }
            }
    }
}
