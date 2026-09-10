package ir.aut.supplementtracker.feature.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.aut.supplementtracker.core.domain.ErrorMapper
import ir.aut.supplementtracker.core.domain.ListProductsUseCase
import ir.aut.supplementtracker.core.model.ProductListQuery
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StockViewModel(
    private val listProducts: ListProductsUseCase,
    mode: StockMode,
    ownerAddress: String,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            StockUiState(
                mode = mode,
                ownerAddress = ownerAddress,
            ),
        )
    val state: StateFlow<StockUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StockUiEffect>()
    val effects: SharedFlow<StockUiEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun onEvent(event: StockUiEvent) {
        when (event) {
            StockUiEvent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        val current = _state.value
        if (current.ownerAddress.isBlank()) {
            _state.update {
                it.copy(errorMessage = "OWNER_ADDRESS_REQUIRED", isLoading = false)
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                listProducts(
                    ProductListQuery(
                        owner = current.ownerAddress,
                        page = 1,
                        limit = 50,
                    ),
                )
            }.onSuccess { page ->
                _state.update { it.copy(isLoading = false, items = page.items) }
            }.onFailure { error ->
                val message = ErrorMapper.toUserMessage(error)
                _state.update { it.copy(isLoading = false, errorMessage = message) }
                _effects.emit(StockUiEffect.ShowMessage(message))
            }
        }
    }

    companion object {
        fun factory(
            listProducts: ListProductsUseCase,
            mode: StockMode,
            ownerAddress: String,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StockViewModel(listProducts, mode, ownerAddress) as T
                }
            }
    }
}
