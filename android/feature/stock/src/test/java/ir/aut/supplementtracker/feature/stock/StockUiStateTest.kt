package ir.aut.supplementtracker.feature.stock

import ir.aut.supplementtracker.core.model.Segment
import ir.aut.supplementtracker.core.model.SupplyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockUiStateTest {
    private val items =
        listOf(
            Segment("1", "4", "0xd", 0, 8, 8, "Transferred"),
            Segment("2", "4", "0xd", 8, 15, 7, "Transferred"),
        )

    @Test
    fun totalsUnitsAcrossSegments() {
        assertEquals(15, StockUiState(items = items).totalUnits)
    }

    @Test
    fun onlyDistributorsCanTransferFromStock() {
        assertTrue(StockUiState(mode = StockMode.Distributor).canTransfer)
        assertFalse(StockUiState(mode = StockMode.Pharmacy).canTransfer)
    }

    @Test
    fun mapsSupplyRolesOntoStockModes() {
        assertEquals(StockMode.Distributor, SupplyRole.Distributor.toStockMode())
        assertEquals(StockMode.Pharmacy, SupplyRole.Pharmacy.toStockMode())
        assertEquals(StockMode.Distributor, SupplyRole.Admin.toStockMode())
        assertNull(SupplyRole.Manufacturer.toStockMode())
    }
}
