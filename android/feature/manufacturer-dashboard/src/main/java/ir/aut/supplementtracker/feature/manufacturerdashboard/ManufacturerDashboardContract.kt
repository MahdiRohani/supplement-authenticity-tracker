package ir.aut.supplementtracker.feature.manufacturerdashboard

import ir.aut.supplementtracker.core.model.ProductSummary
import ir.aut.supplementtracker.core.model.RegisterBatchResult

data class ManufacturerDashboardUiState(
    val search: String = "",
    val statusFilter: String? = null,
    val items: List<ProductSummary> = emptyList(),
    val isLoading: Boolean = false,
    val batchName: String = "",
    val batchCode: String = "",
    val batchCount: String = "1",
    val isSubmitting: Boolean = false,
    val isExporting: Boolean = false,
    val completedCount: Int = 0,
    val targetCount: Int = 0,
    val batchResult: RegisterBatchResult? = null,
    val errorMessage: String? = null,
    val labelsPdfEnabled: Boolean = true,
    val analyticsEnabled: Boolean = false,
    val analyticsVerifyCount: Int = 0,
    val analyticsScanCount: Int = 0,
)

sealed interface ManufacturerDashboardUiEvent {
    data class SearchChanged(val value: String) : ManufacturerDashboardUiEvent
    data class StatusFilterChanged(val status: String?) : ManufacturerDashboardUiEvent
    data object Refresh : ManufacturerDashboardUiEvent
    data class BatchNameChanged(val value: String) : ManufacturerDashboardUiEvent
    data class BatchCodeChanged(val value: String) : ManufacturerDashboardUiEvent
    data class BatchCountChanged(val value: String) : ManufacturerDashboardUiEvent
    data object SubmitBatch : ManufacturerDashboardUiEvent
    data object ExportLabelsPdf : ManufacturerDashboardUiEvent
}

sealed interface ManufacturerDashboardUiEffect {
    data class ShowMessage(val message: String) : ManufacturerDashboardUiEffect
    data class BatchRegistered(val count: Int) : ManufacturerDashboardUiEffect
    data class SharePdf(val bytes: ByteArray, val batchCode: String) : ManufacturerDashboardUiEffect
}
