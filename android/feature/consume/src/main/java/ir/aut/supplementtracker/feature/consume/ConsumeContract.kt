package ir.aut.supplementtracker.feature.consume

import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.UnitConsumeResult
import ir.aut.supplementtracker.core.model.UnitRef

data class ConsumeUiState(
    /** Unit whose open label was verified before coming here, if any. */
    val expected: UnitRef? = null,
    val secretInput: String = "",
    val label: SecretLabel? = null,
    val isSubmitting: Boolean = false,
    val result: UnitConsumeResult? = null,
    val errorMessage: String? = null,
    val scanEnabled: Boolean = true,
) {
    /** The hidden code belongs to another unit than the one just verified. */
    val mismatch: Boolean get() = expected != null && label != null && label.unit != expected

    val canSubmit: Boolean get() = !isSubmitting && label != null && !mismatch && result == null
}

sealed interface ConsumeUiEvent {
    data class SecretChanged(val value: String) : ConsumeUiEvent
    data object ScanHiddenCode : ConsumeUiEvent
    data object Submit : ConsumeUiEvent
}

sealed interface ConsumeUiEffect {
    data class ShowMessage(val message: String) : ConsumeUiEffect
    data object NavigateToScan : ConsumeUiEffect
    data class Consumed(val unit: UnitRef) : ConsumeUiEffect
}
