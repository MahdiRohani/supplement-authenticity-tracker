package ir.aut.supplementtracker.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ListCustodySegmentsUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StockViewModel(
    private val listSegments: ListCustodySegmentsUseCase,
    mode: StockMode,
    ownerAddress: String,
) : ViewModel() {
    private val _state = MutableStateFlow(StockUiState(mode = mode, ownerAddress = ownerAddress))
    val state: StateFlow<StockUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StockUiEffect>()
    val effects: SharedFlow<StockUiEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onEvent(event: StockUiEvent) {
        when (event) {
            StockUiEvent.Refresh -> refresh()
            is StockUiEvent.TransferSegment ->
                viewModelScope.launch {
                    if (_state.value.canTransfer) _effects.emit(StockUiEffect.NavigateToTransfer(event.segmentId))
                }
        }
    }

    private fun refresh() {
        val current = _state.value
        if (current.ownerAddress.isBlank()) {
            _state.update { it.copy(errorMessage = "OWNER_ADDRESS_REQUIRED", isLoading = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { listSegments(current.ownerAddress) }
                .onSuccess { segments -> _state.update { it.copy(isLoading = false, items = segments) } }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    _state.update { it.copy(isLoading = false, errorMessage = message) }
                    _effects.emit(StockUiEffect.ShowMessage(message))
                }
        }
    }

    companion object {
        fun factory(
            listSegments: ListCustodySegmentsUseCase,
            mode: StockMode,
            ownerAddress: String,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StockViewModel(listSegments, mode, ownerAddress) as T
                }
            }
    }
}
