package ir.aut.supplementtracker.feature.stock

import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SupplyRole

enum class StockMode {
    Distributor,
    Pharmacy,
}

data class StockUiState(
    val mode: StockMode = StockMode.Distributor,
    val ownerAddress: String = "",
    /** Contiguous unit ranges this address holds, one row per custody segment. */
    val items: List<Segment> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val totalUnits: Int get() = items.sumOf { it.units }

    /** Pharmacies sell what they hold; distributors pass it on. */
    val canTransfer: Boolean get() = mode == StockMode.Distributor
}

sealed interface StockUiEvent {
    data object Refresh : StockUiEvent
    data class TransferSegment(val segmentId: String) : StockUiEvent
}

sealed interface StockUiEffect {
    data class ShowMessage(val message: String) : StockUiEffect
    data class NavigateToTransfer(val segmentId: String) : StockUiEffect
}

fun SupplyRole.toStockMode(): StockMode? =
    when (this) {
        SupplyRole.Distributor -> StockMode.Distributor
        SupplyRole.Pharmacy -> StockMode.Pharmacy
        SupplyRole.Admin -> StockMode.Distributor
        SupplyRole.Manufacturer -> null
    }
