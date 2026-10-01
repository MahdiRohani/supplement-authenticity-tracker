package ir.aut.supplementtracker.feature.manufacturerregister

import ir.aut.supplementtracker.core.model.RegisteredBatch

data class ManufacturerRegisterUiState(
    val name: String = "",
    val lotCode: String = "",
    val size: String = "100",
    /** Optional `YYYY-MM-DD`; published in the IPFS manifest. */
    val expiresAt: String = "",
    val isSubmitting: Boolean = false,
    val isExporting: Boolean = false,
    /** Holds the per-unit secret QRs until the labels are printed and the keys discarded. */
    val result: RegisteredBatch? = null,
    val labelsExported: Boolean = false,
    val errorMessage: String? = null,
    val labelsPdfEnabled: Boolean = true,
    val maxSize: Int = 5_000,
) {
    val sizeValue: Int? get() = size.trim().toIntOrNull()
    val sizeValid: Boolean get() = sizeValue?.let { it in 1..maxSize } ?: false
    val expiresValid: Boolean get() = expiresAt.isBlank() || EXPIRY.matches(expiresAt.trim())
    val canSubmit: Boolean
        get() = !isSubmitting && name.isNotBlank() && lotCode.isNotBlank() && sizeValid && expiresValid
    val keysHeld: Boolean get() = result?.units?.isNotEmpty() == true

    private companion object {
        val EXPIRY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    }
}

sealed interface ManufacturerRegisterUiEvent {
    data class NameChanged(val value: String) : ManufacturerRegisterUiEvent
    data class LotCodeChanged(val value: String) : ManufacturerRegisterUiEvent
    data class SizeChanged(val value: String) : ManufacturerRegisterUiEvent
    data class ExpiresAtChanged(val value: String) : ManufacturerRegisterUiEvent
    data object Submit : ManufacturerRegisterUiEvent
    data object ExportLabels : ManufacturerRegisterUiEvent
    data object DiscardKeys : ManufacturerRegisterUiEvent
    data object StartOver : ManufacturerRegisterUiEvent
}

sealed interface ManufacturerRegisterUiEffect {
    data class ShowMessage(val message: String) : ManufacturerRegisterUiEffect
    data class Registered(val batchId: String, val size: Int) : ManufacturerRegisterUiEffect
    data class ShareLabels(val bytes: ByteArray, val fileName: String) : ManufacturerRegisterUiEffect
}
