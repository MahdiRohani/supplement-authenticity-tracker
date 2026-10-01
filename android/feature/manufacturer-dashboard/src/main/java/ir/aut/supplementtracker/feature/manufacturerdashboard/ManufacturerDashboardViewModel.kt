package ir.aut.supplementtracker.feature.manufacturerdashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.GetBatchDetailUseCase
import ir.aut.supplementtracker.core.domain.ListBatchesUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManufacturerDashboardViewModel(
    private val listBatches: ListBatchesUseCase,
    private val getBatchDetail: GetBatchDetailUseCase,
    private val manufacturerAddress: String? = null,
    analyticsEnabled: Boolean = false,
    analyticsVerifyCount: Int = 0,
    analyticsScanCount: Int = 0,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            ManufacturerDashboardUiState(
                analyticsEnabled = analyticsEnabled,
                analyticsVerifyCount = analyticsVerifyCount,
                analyticsScanCount = analyticsScanCount,
            ),
        )
    val state: StateFlow<ManufacturerDashboardUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ManufacturerDashboardUiEffect>()
    val effects: SharedFlow<ManufacturerDashboardUiEffect> = _effects.asSharedFlow()

    private var detailJob: Job? = null

    init {
        refresh()
    }

    fun onEvent(event: ManufacturerDashboardUiEvent) {
        when (event) {
            is ManufacturerDashboardUiEvent.SearchChanged ->
                _state.update { it.copy(search = event.value) }
            ManufacturerDashboardUiEvent.Refresh -> refresh()
            is ManufacturerDashboardUiEvent.BatchSelected -> select(event.batchId)
            ManufacturerDashboardUiEvent.NewBatch ->
                viewModelScope.launch { _effects.emit(ManufacturerDashboardUiEffect.NavigateToRegister) }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { listBatches(manufacturerAddress) }
                .onSuccess { page ->
                    _state.update { it.copy(isLoading = false, items = page.items) }
                    _state.value.selectedBatchId?.let(::loadDetail)
                }
                .onFailure { error ->
                    val message = ErrorMapper.toUserMessage(error)
                    _state.update { it.copy(isLoading = false, errorMessage = message) }
                    _effects.emit(ManufacturerDashboardUiEffect.ShowMessage(message))
                }
        }
    }

    private fun select(batchId: String) {
        if (_state.value.selectedBatchId == batchId) {
            detailJob?.cancel()
            _state.update { it.copy(selectedBatchId = null, detail = null, isLoadingDetail = false) }
            return
        }
        _state.update { it.copy(selectedBatchId = batchId, detail = null) }
        loadDetail(batchId)
    }

    private fun loadDetail(batchId: String) {
        detailJob?.cancel()
        detailJob =
            viewModelScope.launch {
                _state.update { it.copy(isLoadingDetail = true) }
                runCatching { getBatchDetail(batchId) }
                    .onSuccess { detail ->
                        _state.update {
                            if (it.selectedBatchId != batchId) it else it.copy(isLoadingDetail = false, detail = detail)
                        }
                    }
                    .onFailure { error ->
                        val message = ErrorMapper.toUserMessage(error)
                        _state.update { it.copy(isLoadingDetail = false, errorMessage = message) }
                    }
            }
    }

    companion object {
        fun factory(
            listBatches: ListBatchesUseCase,
            getBatchDetail: GetBatchDetailUseCase,
            manufacturerAddress: String? = null,
            analyticsEnabled: Boolean = false,
            analyticsVerifyCount: Int = 0,
            analyticsScanCount: Int = 0,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManufacturerDashboardViewModel(
                        listBatches = listBatches,
                        getBatchDetail = getBatchDetail,
                        manufacturerAddress = manufacturerAddress,
                        analyticsEnabled = analyticsEnabled,
                        analyticsVerifyCount = analyticsVerifyCount,
                        analyticsScanCount = analyticsScanCount,
                    ) as T
                }
            }
    }
}
