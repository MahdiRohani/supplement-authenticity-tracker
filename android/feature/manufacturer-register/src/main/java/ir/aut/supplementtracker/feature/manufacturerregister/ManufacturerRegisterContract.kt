package ir.aut.supplementtracker.feature.manufacturerregister

import ir.aut.supplementtracker.core.model.RegisteredProduct

data class ManufacturerRegisterUiState(
    val name: String = "",
    val batch: String = "",
    val isSubmitting: Boolean = false,
    val result: RegisteredProduct? = null,
    val errorMessage: String? = null,
)

sealed interface ManufacturerRegisterUiEvent {
    data class NameChanged(val value: String) : ManufacturerRegisterUiEvent
    data class BatchChanged(val value: String) : ManufacturerRegisterUiEvent
    data object Submit : ManufacturerRegisterUiEvent
    data object ClearResult : ManufacturerRegisterUiEvent
}

sealed interface ManufacturerRegisterUiEffect {
    data class ShowMessage(val message: String) : ManufacturerRegisterUiEffect
}
