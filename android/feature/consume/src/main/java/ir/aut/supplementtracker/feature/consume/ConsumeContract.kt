package ir.aut.supplementtracker.feature.consume

import ir.aut.supplementtracker.core.model.ConsumeResult

data class ConsumeUiState(
    val productId: String = "",
    val secret: String = "",
    val isSubmitting: Boolean = false,
    val result: ConsumeResult? = null,
    val errorMessage: String? = null,
)

sealed interface ConsumeUiEvent {
    data class ProductIdChanged(val value: String) : ConsumeUiEvent
    data class SecretChanged(val value: String) : ConsumeUiEvent
    data object Submit : ConsumeUiEvent
}

sealed interface ConsumeUiEffect {
    data class ShowMessage(val message: String) : ConsumeUiEffect
    data class Consumed(val chainProductId: String) : ConsumeUiEffect
}
