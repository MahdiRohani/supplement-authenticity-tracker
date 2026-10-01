package ir.aut.supplementtracker.feature.manufacturerregister

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.RegisterUnitBatchUseCase
import ir.aut.supplementtracker.core.domain.RenderBatchLabelsUseCase
import ir.aut.supplementtracker.core.model.NewBatchRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManufacturerRegisterViewModel(
    private val registerBatch: RegisterUnitBatchUseCase,
    private val renderLabels: RenderBatchLabelsUseCase? = null,
    private val manufacturerAddress: String? = null,
    labelsPdfEnabled: Boolean = true,
    maxSize: Int = RegisterUnitBatchUseCase.DEFAULT_MAX_SIZE,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            ManufacturerRegisterUiState(
                labelsPdfEnabled = labelsPdfEnabled && renderLabels != null,
                maxSize = maxSize,
            ),
        )
    val state: StateFlow<ManufacturerRegisterUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ManufacturerRegisterUiEffect>()
    val effects: SharedFlow<ManufacturerRegisterUiEffect> = _effects.asSharedFlow()

    fun onEvent(event: ManufacturerRegisterUiEvent) {
        when (event) {
            is ManufacturerRegisterUiEvent.NameChanged ->
                _state.update { it.copy(name = event.value, errorMessage = null) }
            is ManufacturerRegisterUiEvent.LotCodeChanged ->
                _state.update { it.copy(lotCode = event.value, errorMessage = null) }
            is ManufacturerRegisterUiEvent.SizeChanged ->
                _state.update { it.copy(size = event.value.filter(Char::isDigit).take(5), errorMessage = null) }
            is ManufacturerRegisterUiEvent.ExpiresAtChanged ->
                _state.update { it.copy(expiresAt = event.value.take(10), errorMessage = null) }
            ManufacturerRegisterUiEvent.Submit -> submit()
            ManufacturerRegisterUiEvent.ExportLabels -> exportLabels()
            ManufacturerRegisterUiEvent.DiscardKeys ->
                _state.update { it.copy(result = it.result?.copy(units = emptyList())) }
            ManufacturerRegisterUiEvent.StartOver ->
                _state.update {
                    ManufacturerRegisterUiState(labelsPdfEnabled = it.labelsPdfEnabled, maxSize = it.maxSize)
                }
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit || current.keysHeld) return
        val size = current.sizeValue ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, errorMessage = null, labelsExported = false) }
            runCatching {
                registerBatch(
                    NewBatchRequest(
                        name = current.name,
                        lotCode = current.lotCode,
                        size = size,
                        manufacturerAddress = manufacturerAddress,
                        expiresAt = current.expiresAt.trim().ifBlank { null },
                    ),
                )
            }.onSuccess { registered ->
                _state.update { it.copy(isSubmitting = false, result = registered) }
                _effects.emit(ManufacturerRegisterUiEffect.Registered(registered.batch.batchId, registered.batch.size))
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update { it.copy(isSubmitting = false, errorMessage = message) }
                _effects.emit(ManufacturerRegisterUiEffect.ShowMessage(message))
            }
        }
    }

    private fun exportLabels() {
        val current = _state.value
        val batch = current.result ?: return
        val useCase = renderLabels ?: return
        if (current.isExporting || !current.labelsPdfEnabled) return
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true, errorMessage = null) }
            runCatching { useCase(batch) }
                .onSuccess { bytes ->
                    _state.update { it.copy(isExporting = false, labelsExported = true) }
                    val lot = batch.batch.lotCode?.takeIf { it.isNotBlank() } ?: "batch"
                    _effects.emit(
                        ManufacturerRegisterUiEffect.ShareLabels(bytes, "labels-${batch.batch.batchId}-$lot"),
                    )
                }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    _state.update { it.copy(isExporting = false, errorMessage = message) }
                    _effects.emit(ManufacturerRegisterUiEffect.ShowMessage(message))
                }
        }
    }

    companion object {
        fun factory(
            registerBatch: RegisterUnitBatchUseCase,
            renderLabels: RenderBatchLabelsUseCase? = null,
            manufacturerAddress: String? = null,
            labelsPdfEnabled: Boolean = true,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManufacturerRegisterViewModel(
                        registerBatch = registerBatch,
                        renderLabels = renderLabels,
                        manufacturerAddress = manufacturerAddress,
                        labelsPdfEnabled = labelsPdfEnabled,
                    ) as T
                }
            }
    }
}
