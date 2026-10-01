package ir.aut.supplementtracker.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ListCustodySegmentsUseCase
import ir.aut.supplementtracker.core.domain.TransferSegmentUseCase
import ir.aut.supplementtracker.core.model.SegmentStatus
import ir.aut.supplementtracker.core.model.SegmentTransferRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransferViewModel(
    private val listSegments: ListCustodySegmentsUseCase,
    private val transferSegment: TransferSegmentUseCase,
    ownerAddress: String,
    initialSegmentId: String? = null,
) : ViewModel() {
    private val _state =
        MutableStateFlow(TransferUiState(ownerAddress = ownerAddress, selectedSegmentId = initialSegmentId))
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<TransferUiEffect>()
    val effects: SharedFlow<TransferUiEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onEvent(event: TransferUiEvent) {
        when (event) {
            TransferUiEvent.Refresh -> refresh()
            is TransferUiEvent.SegmentSelected ->
                _state.update { it.copy(selectedSegmentId = event.segmentId, count = "", errorMessage = null) }
            is TransferUiEvent.CountChanged ->
                _state.update { it.copy(count = event.value.filter(Char::isDigit).take(6), errorMessage = null) }
            is TransferUiEvent.ToAddressChanged ->
                _state.update { it.copy(toAddress = event.value, errorMessage = null) }
            TransferUiEvent.Submit -> submit()
        }
    }

    private fun refresh() {
        val owner = _state.value.ownerAddress
        if (owner.isBlank()) {
            _state.update { it.copy(errorMessage = "OWNER_ADDRESS_REQUIRED") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoadingSegments = true) }
            runCatching { listSegments(owner) }
                .onSuccess { all ->
                    // Units at a pharmacy are for sale, not for passing on.
                    val movable = all.filter { it.status != SegmentStatus.AT_POINT_OF_SALE }
                    _state.update { s ->
                        val keep = s.selectedSegmentId?.takeIf { id -> movable.any { it.segmentId == id } }
                        s.copy(
                            isLoadingSegments = false,
                            segments = movable,
                            selectedSegmentId = keep ?: movable.firstOrNull()?.segmentId,
                        )
                    }
                }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    _state.update { it.copy(isLoadingSegments = false, errorMessage = message) }
                }
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        val segment = current.selected ?: return
        val count = current.countValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null, result = null) }
            runCatching {
                transferSegment(
                    SegmentTransferRequest(
                        segmentId = segment.segmentId,
                        toAddress = current.toAddress,
                        count = count,
                    ),
                    available = segment.units,
                )
            }.onSuccess { result ->
                _state.update { it.copy(isSubmitting = false, result = result, count = "", selectedSegmentId = null) }
                _effects.emit(TransferUiEffect.Transferred(result.txHash))
                refresh()
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update { it.copy(isSubmitting = false, errorMessage = message) }
                _effects.emit(TransferUiEffect.ShowMessage(message))
            }
        }
    }

    companion object {
        fun factory(
            listSegments: ListCustodySegmentsUseCase,
            transferSegment: TransferSegmentUseCase,
            ownerAddress: String,
            initialSegmentId: String? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TransferViewModel(listSegments, transferSegment, ownerAddress, initialSegmentId) as T
                }
            }
    }
}
