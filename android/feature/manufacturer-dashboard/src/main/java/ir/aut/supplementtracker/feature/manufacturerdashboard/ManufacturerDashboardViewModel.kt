package ir.aut.supplementtracker.feature.manufacturerdashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ListProductsUseCase
import ir.aut.supplementtracker.core.domain.RegisterBatchUseCase
import ir.aut.supplementtracker.core.model.ProductListQuery
import ir.aut.supplementtracker.core.model.RegisterBatchRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManufacturerDashboardViewModel(
    private val listProducts: ListProductsUseCase,
    private val registerBatch: RegisterBatchUseCase,
    private val ownerAddress: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ManufacturerDashboardUiState())
    val state: StateFlow<ManufacturerDashboardUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ManufacturerDashboardUiEffect>()
    val effects: SharedFlow<ManufacturerDashboardUiEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onEvent(event: ManufacturerDashboardUiEvent) {
        when (event) {
            is ManufacturerDashboardUiEvent.SearchChanged ->
                _state.update { it.copy(search = event.value, errorMessage = null) }
            is ManufacturerDashboardUiEvent.StatusFilterChanged -> {
                _state.update { it.copy(statusFilter = event.status, errorMessage = null) }
                refresh()
            }
            ManufacturerDashboardUiEvent.Refresh -> refresh()
            is ManufacturerDashboardUiEvent.BatchNameChanged ->
                _state.update { it.copy(batchName = event.value, errorMessage = null) }
            is ManufacturerDashboardUiEvent.BatchCodeChanged ->
                _state.update { it.copy(batchCode = event.value, errorMessage = null) }
            is ManufacturerDashboardUiEvent.BatchCountChanged ->
                _state.update { it.copy(batchCount = event.value, errorMessage = null) }
            ManufacturerDashboardUiEvent.SubmitBatch -> submitBatch()
        }
    }

    private fun refresh() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                listProducts(
                    ProductListQuery(
                        owner = ownerAddress,
                        status = current.statusFilter,
                        q = current.search.trim().ifBlank { null },
                        page = 1,
                        limit = 50,
                    ),
                )
            }.onSuccess { page ->
                _state.update {
                    it.copy(isLoading = false, items = page.items)
                }
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update { it.copy(isLoading = false, errorMessage = message) }
                _effects.emit(ManufacturerDashboardUiEffect.ShowMessage(message))
            }
        }
    }

    private fun submitBatch() {
        val current = _state.value
        if (current.isSubmitting) return
        val count = current.batchCount.trim().toIntOrNull() ?: 0
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isSubmitting = true,
                    completedCount = 0,
                    targetCount = count,
                    errorMessage = null,
                    batchResult = null,
                )
            }
            runCatching {
                registerBatch(
                    RegisterBatchRequest(
                        name = current.batchName.trim(),
                        batch = current.batchCode.trim(),
                        count = count,
                        manufacturerAddress = ownerAddress,
                    ),
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        completedCount = result.count,
                        targetCount = result.count,
                        batchResult = result,
                    )
                }
                _effects.emit(
                    ManufacturerDashboardUiEffect.ShowMessage("Batch registered ${result.count}"),
                )
                refresh()
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update {
                    it.copy(isSubmitting = false, errorMessage = message)
                }
                _effects.emit(ManufacturerDashboardUiEffect.ShowMessage(message))
            }
        }
    }

    companion object {
        fun factory(
            listProducts: ListProductsUseCase,
            registerBatch: RegisterBatchUseCase,
            ownerAddress: String? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ManufacturerDashboardViewModel(
                        listProducts,
                        registerBatch,
                        ownerAddress,
                    ) as T
                }
            }
    }
}
