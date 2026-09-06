package ir.aut.supplementtracker.feature.manufacturerregister

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.core.model.RegisterProductRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManufacturerRegisterViewModel(
    private val registerProduct: RegisterProductUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ManufacturerRegisterUiState())
    val state: StateFlow<ManufacturerRegisterUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ManufacturerRegisterUiEffect>()
    val effects: SharedFlow<ManufacturerRegisterUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: ManufacturerRegisterUiEvent) {
        when (event) {
            is ManufacturerRegisterUiEvent.NameChanged ->
                _state.update { it.copy(name = event.value, errorMessage = null) }
            is ManufacturerRegisterUiEvent.BatchChanged ->
                _state.update { it.copy(batch = event.value, errorMessage = null) }
            ManufacturerRegisterUiEvent.ClearResult ->
                _state.update { it.copy(result = null) }
            ManufacturerRegisterUiEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null) }
            runCatching {
                registerProduct(
                    RegisterProductRequest(
                        name = current.name.trim(),
                        batch = current.batch.trim(),
                    ),
                )
            }.onSuccess { product ->
                _state.update {
                    it.copy(isSubmitting = false, result = product)
                }
                _effects.emit(
                    ManufacturerRegisterUiEffect.ShowMessage(
                        "Registered ${product.chainProductId}",
                    ),
                )
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Registration failed",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(registerProduct: RegisterProductUseCase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManufacturerRegisterViewModel(registerProduct) as T
                }
            }
    }
}
