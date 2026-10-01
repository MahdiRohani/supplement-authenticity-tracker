package ir.aut.supplementtracker.feature.manufacturerdashboard

import ir.aut.supplementtracker.core.model.Batch
import ir.aut.supplementtracker.core.model.BatchDetail

data class ManufacturerDashboardUiState(
    val search: String = "",
    val items: List<Batch> = emptyList(),
    val isLoading: Boolean = false,
    val selectedBatchId: String? = null,
    val detail: BatchDetail? = null,
    val isLoadingDetail: Boolean = false,
    val errorMessage: String? = null,
    val analyticsEnabled: Boolean = false,
    val analyticsVerifyCount: Int = 0,
    val analyticsScanCount: Int = 0,
) {
    val visibleItems: List<Batch>
        get() {
            val q = search.trim()
            if (q.isEmpty()) return items
            return items.filter {
                it.batchId == q.removePrefix("#") ||
                    it.name?.contains(q, ignoreCase = true) == true ||
                    it.lotCode?.contains(q, ignoreCase = true) == true
            }
        }

    val totalUnits: Int get() = items.sumOf { it.size }
    val consumedUnits: Int get() = items.sumOf { it.consumedCount }
    val recalledBatches: Int get() = items.count { it.recalled }
}

sealed interface ManufacturerDashboardUiEvent {
    data class SearchChanged(val value: String) : ManufacturerDashboardUiEvent
    data object Refresh : ManufacturerDashboardUiEvent
    data class BatchSelected(val batchId: String) : ManufacturerDashboardUiEvent
    data object NewBatch : ManufacturerDashboardUiEvent
}

sealed interface ManufacturerDashboardUiEffect {
    data class ShowMessage(val message: String) : ManufacturerDashboardUiEffect
    data object NavigateToRegister : ManufacturerDashboardUiEffect
}
