package ir.aut.supplementtracker.feature.stock

import ir.aut.supplementtracker.core.model.ProductSummary
import ir.aut.supplementtracker.core.model.SupplyRole

enum class StockMode {
    Distributor,
    Pharmacy,
}

data class StockUiState(
    val mode: StockMode = StockMode.Distributor,
    val ownerAddress: String = "",
    val items: List<ProductSummary> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface StockUiEvent {
    data object Refresh : StockUiEvent
}

sealed interface StockUiEffect {
    data class ShowMessage(val message: String) : StockUiEffect
}

fun SupplyRole.toStockMode(): StockMode? =
    when (this) {
        SupplyRole.Distributor -> StockMode.Distributor
        SupplyRole.Pharmacy -> StockMode.Pharmacy
        SupplyRole.Admin -> StockMode.Distributor
        SupplyRole.Manufacturer -> null
    }
