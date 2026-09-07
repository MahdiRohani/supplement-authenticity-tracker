package ir.aut.supplementtracker.feature.consume

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ConsumeProductUseCase
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.model.ConsumeRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConsumeViewModel(
    private val consumeProduct: ConsumeProductUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsumeUiState())
    val state: StateFlow<ConsumeUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ConsumeUiEffect>()
    val effects: SharedFlow<ConsumeUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: ConsumeUiEvent) {
        when (event) {
            is ConsumeUiEvent.ProductIdChanged ->
                _state.update { it.copy(productId = event.value, errorMessage = null) }
            is ConsumeUiEvent.SecretChanged ->
                _state.update { it.copy(secret = event.value, errorMessage = null) }
            ConsumeUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null) }
            runCatching {
                consumeProduct(
                    ConsumeRequest(
                        productId = current.productId.trim(),
                        secret = current.secret.trim(),
                    ),
                )
            }.onSuccess { result ->
                _state.update { it.copy(isSubmitting = false, result = result, secret = "") }
                _effects.emit(ConsumeUiEffect.ShowMessage("Consumed ${result.chainProductId}"))
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update {
                    it.copy(isSubmitting = false, errorMessage = message)
                }
                _effects.emit(ConsumeUiEffect.ShowMessage(message))
            }
        }
    }

    companion object {
        fun factory(consumeProduct: ConsumeProductUseCase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConsumeViewModel(consumeProduct) as T
                }
            }
    }
}
