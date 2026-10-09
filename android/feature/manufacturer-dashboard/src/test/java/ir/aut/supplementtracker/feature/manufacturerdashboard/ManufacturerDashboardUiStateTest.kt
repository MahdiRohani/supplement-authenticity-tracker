package ir.aut.supplementtracker.feature.manufacturerdashboard

import ir.aut.supplementtracker.core.model.Batch
import org.junit.Assert.assertEquals
import org.junit.Test

class ManufacturerDashboardUiStateTest {
    private val items =
        listOf(
            Batch("1", "0xm", 100, 4, false, name = "Vitamin D3", lotCode = "LOT-A"),
            Batch("2", "0xm", 50, 50, true, name = "Omega-3", lotCode = "LOT-B"),
            Batch("3", "0xm", 20, 0, false, name = "Vitamin C", lotCode = "LOT-C"),
        )

    @Test
    fun aggregatesUnitsConsumedAndRecalls() {
        val state = ManufacturerDashboardUiState(items = items)
        assertEquals(170, state.totalUnits)
        assertEquals(54, state.consumedUnits)
        assertEquals(1, state.recalledBatches)
    }

    @Test
    fun searchMatchesBatchIdNameAndLot() {
        val state = ManufacturerDashboardUiState(items = items)
        assertEquals(listOf("1"), state.copy(search = "#1").visibleItems.map { it.batchId })
        assertEquals(listOf("2"), state.copy(search = "omega").visibleItems.map { it.batchId })
        assertEquals(listOf("3"), state.copy(search = "LOT-C").visibleItems.map { it.batchId })
        assertEquals(items, state.copy(search = "  ").visibleItems)
    }
}
