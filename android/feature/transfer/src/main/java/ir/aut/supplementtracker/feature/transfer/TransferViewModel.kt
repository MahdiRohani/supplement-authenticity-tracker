package ir.aut.supplementtracker.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.TransferProductUseCase
import ir.aut.supplementtracker.core.model.TransferRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransferViewModel(
    private val transferProduct: TransferProductUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TransferUiEffect>()
    val effects: SharedFlow<TransferUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: TransferUiEvent) {
        when (event) {
            is TransferUiEvent.ProductIdChanged ->
                _state.update { it.copy(productId = event.value, errorMessage = null) }
            is TransferUiEvent.ToAddressChanged ->
                _state.update { it.copy(toAddress = event.value, errorMessage = null) }
            TransferUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null) }
            runCatching {
                transferProduct(
                    TransferRequest(
                        productId = current.productId.trim(),
                        toAddress = current.toAddress.trim(),
                    ),
                )
            }.onSuccess { result ->
                _state.update { it.copy(isSubmitting = false, result = result) }
                _effects.emit(TransferUiEffect.ShowMessage("Transferred ${result.txHash}"))
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Transfer failed",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(transferProduct: TransferProductUseCase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TransferViewModel(transferProduct) as T
                }
            }
    }
}
