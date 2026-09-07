package ir.aut.supplementtracker.feature.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.domain.VerifyProductUseCase
import ir.aut.supplementtracker.core.model.QrPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VerifyViewModel(
    private val verifyProduct: VerifyProductUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VerifyUiEffect>()
    val effects: SharedFlow<VerifyUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: VerifyUiEvent) {
        when (event) {
            is VerifyUiEvent.InputChanged ->
                _state.update {
                    it.copy(
                        input = event.value,
                        errorMessage = null,
                        authenticityStatus = null,
                    )
                }
            VerifyUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isLoading) return
        val payload = QrPayload.parse(current.input.trim())
        val productId = payload?.productId ?: current.input.trim()
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, result = null) }
            runCatching { verifyProduct(productId) }
                .onSuccess { result ->
                    val status = result.toAuthenticityStatus()
                    _state.update {
                        it.copy(
                            isLoading = false,
                            result = result,
                            authenticityStatus = status,
                        )
                    }
                    _effects.emit(VerifyUiEffect.ShowMessage(status.name))
                }
                .onFailure { error ->
                    val network = error.message?.contains("Unable to resolve", true) == true ||
                        error.message?.contains("failed to connect", true) == true ||
                        error.message?.contains("API failed", true) == true
                    _state.update {
                        it.copy(
                            isLoading = false,
                            authenticityStatus = if (network) {
                                AuthenticityStatus.NetworkError
                            } else {
                                AuthenticityStatus.NotFound
                            },
                            errorMessage = error.message ?: "Verify failed",
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(verifyProduct: VerifyProductUseCase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return VerifyViewModel(verifyProduct) as T
                }
            }
    }
}
