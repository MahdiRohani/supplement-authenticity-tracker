package ir.aut.supplementtracker.feature.consume

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ConsumeUnitUseCase
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.UnitRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NOT_A_HIDDEN_LABEL = "NOT_A_HIDDEN_LABEL"

class ConsumeViewModel(
    private val consumeUnit: ConsumeUnitUseCase,
    expected: UnitRef? = null,
    scanEnabled: Boolean = true,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsumeUiState(expected = expected, scanEnabled = scanEnabled))
    val state: StateFlow<ConsumeUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ConsumeUiEffect>()
    val effects: SharedFlow<ConsumeUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: ConsumeUiEvent) {
        when (event) {
            is ConsumeUiEvent.SecretChanged -> {
                val label = SecretLabel.parse(event.value)
                _state.update {
                    it.copy(
                        secretInput = event.value,
                        label = label,
                        result = null,
                        errorMessage = if (label == null && event.value.isNotBlank()) NOT_A_HIDDEN_LABEL else null,
                    )
                }
            }
            ConsumeUiEvent.ScanHiddenCode ->
                viewModelScope.launch {
                    if (_state.value.scanEnabled) _effects.emit(ConsumeUiEffect.NavigateToScan)
                }
            ConsumeUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        val label = current.label ?: return
        if (!current.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null) }
            // Key generation and secp256k1 signing stay off the main thread.
            runCatching { withContext(Dispatchers.Default) { consumeUnit(label, current.expected) } }
                .onSuccess { result ->
                    // The one-time key has done its job; drop it from memory and the text field.
                    _state.update { it.copy(isSubmitting = false, result = result, secretInput = "", label = null) }
                    _effects.emit(ConsumeUiEffect.Consumed(label.unit))
                }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    _state.update { it.copy(isSubmitting = false, errorMessage = message) }
                    _effects.emit(ConsumeUiEffect.ShowMessage(message))
                }
        }
    }

    companion object {
        fun factory(
            consumeUnit: ConsumeUnitUseCase,
            expected: UnitRef? = null,
            scanEnabled: Boolean = true,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConsumeViewModel(consumeUnit, expected, scanEnabled) as T
                }
            }
    }
}
