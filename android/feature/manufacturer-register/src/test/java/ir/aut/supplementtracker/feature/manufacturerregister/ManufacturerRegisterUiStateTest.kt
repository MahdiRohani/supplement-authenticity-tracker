package ir.aut.supplementtracker.feature.manufacturerregister

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManufacturerRegisterUiStateTest {
    private val valid = ManufacturerRegisterUiState(name = "Vitamin D3", lotCode = "L-1", size = "100")

    @Test
    fun acceptsACompleteForm() {
        assertTrue(valid.canSubmit)
        assertTrue(valid.copy(expiresAt = "2027-12-31").canSubmit)
    }

    @Test
    fun enforcesBatchBoundsLotCodeAndDateFormat() {
        assertFalse(valid.copy(size = "0").canSubmit)
        assertFalse(valid.copy(size = "5001").canSubmit)
        assertTrue(valid.copy(size = "5000").canSubmit)
        assertFalse(valid.copy(lotCode = " ").canSubmit)
        assertFalse(valid.copy(name = "").canSubmit)
        assertFalse(valid.copy(expiresAt = "31/12/2027").canSubmit)
    }
}
