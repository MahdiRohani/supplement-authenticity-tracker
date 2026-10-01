package ir.aut.supplementtracker.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.GetOwnershipHistoryUseCase
import ir.aut.supplementtracker.core.domain.GetUnitHistoryUseCase
import ir.aut.supplementtracker.core.model.ScannedCode
import ir.aut.supplementtracker.core.model.UnitRef
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
    private val getUnitHistory: GetUnitHistoryUseCase? = null,
    initialInput: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(input = initialInput.orEmpty()))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HistoryUiEffect>()
    val effects: SharedFlow<HistoryUiEffect> = _effects.asSharedFlow()

    init {
        if (!initialInput.isNullOrBlank()) load()
    }

    fun onEvent(event: HistoryUiEvent) {
        when (event) {
            is HistoryUiEvent.InputChanged ->
                _state.update { it.copy(input = event.value, errorMessage = null) }
            HistoryUiEvent.Load -> load()
        }
    }

    private fun load() {
        val current = _state.value
        if (current.isLoading) return
        val raw = current.input.trim()
        if (raw.isEmpty()) return
        when (val code = ScannedCode.parse(raw)) {
            is ScannedCode.Public -> loadUnit(code.ref)
            is ScannedCode.Hidden -> loadUnit(code.label.unit)
            is ScannedCode.Legacy -> loadLegacy(code.productId)
            null -> loadLegacy(raw)
        }
    }

    private fun loadUnit(unit: UnitRef) {
        val useCase = getUnitHistory ?: return loadLegacy(unit.path)
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, history = null, unitHistory = null) }
            val started = System.nanoTime()
            runCatching { useCase(unit) }
                .onSuccess { history ->
                    _state.update { it.copy(isLoading = false, unitHistory = history) }
                    _effects.emit(HistoryUiEffect.Loaded((System.nanoTime() - started) / 1_000_000))
                }
                .onFailure(::fail)
        }
    }

    private fun loadLegacy(productId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, history = null, unitHistory = null) }
            runCatching { getHistory(productId) }
                .onSuccess { history ->
                    _state.update { it.copy(isLoading = false, history = history) }
                    _effects.emit(HistoryUiEffect.Loaded(history.elapsedMs))
                }
                .onFailure(::fail)
        }
    }

    private fun fail(error: Throwable) {
        _state.update { it.copy(isLoading = false, errorMessage = ErrorMapper.toUserMessage(error)) }
    }

    companion object {
        fun factory(
            getHistory: GetOwnershipHistoryUseCase,
            getUnitHistory: GetUnitHistoryUseCase? = null,
            initialInput: String? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(getHistory, getUnitHistory, initialInput) as T
                }
            }
    }
}
