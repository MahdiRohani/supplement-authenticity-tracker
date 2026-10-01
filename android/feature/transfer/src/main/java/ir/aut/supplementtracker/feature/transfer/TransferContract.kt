package ir.aut.supplementtracker.feature.transfer

import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SegmentTransferResult

data class TransferUiState(
    val ownerAddress: String = "",
    val segments: List<Segment> = emptyList(),
    val isLoadingSegments: Boolean = false,
    val selectedSegmentId: String? = null,
    /** How many units to move from the start of the selected segment; empty means all. */
    val count: String = "",
    val toAddress: String = "",
    val isSubmitting: Boolean = false,
    val result: SegmentTransferResult? = null,
    val errorMessage: String? = null,
) {
    val selected: Segment? get() = segments.firstOrNull { it.segmentId == selectedSegmentId }

    val countValue: Int? get() = selected?.let { seg -> if (count.isBlank()) seg.units else count.toIntOrNull() }

    val countValid: Boolean
        get() {
            val seg = selected ?: return false
            val n = countValue ?: return false
            return n in 1..seg.units
        }

    val addressValid: Boolean get() = EVM_ADDRESS.matches(toAddress.trim())

    val canSubmit: Boolean get() = !isSubmitting && countValid && addressValid

    /** The transfer splits the segment: the first [countValue] units move, the rest stay. */
    val isPartial: Boolean get() = selected?.let { seg -> countValue?.let { it in 1 until seg.units } } ?: false

    companion object {
        val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }
}

sealed interface TransferUiEvent {
    data object Refresh : TransferUiEvent
    data class SegmentSelected(val segmentId: String) : TransferUiEvent
    data class CountChanged(val value: String) : TransferUiEvent
    data class ToAddressChanged(val value: String) : TransferUiEvent
    data object Submit : TransferUiEvent
}

sealed interface TransferUiEffect {
    data class ShowMessage(val message: String) : TransferUiEffect
    data class Transferred(val txHash: String) : TransferUiEffect
}
