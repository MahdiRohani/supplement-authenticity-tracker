package ir.aut.supplementtracker.feature.consume

import ir.aut.supplementtracker.core.model.SecretLabel
import ir.aut.supplementtracker.core.model.UnitRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumeUiStateTest {
    private val key = "11".repeat(32)
    private val label = SecretLabel.parse("satk2:31337:7:3:$key")!!

    @Test
    fun hiddenCodeAloneIsEnoughWithoutAPriorVerify() {
        val state = ConsumeUiState(label = label)
        assertFalse(state.mismatch)
        assertTrue(state.canSubmit)
    }

    @Test
    fun hiddenCodeOfAnotherUnitIsBlocked() {
        val state = ConsumeUiState(expected = UnitRef.parse("31337/7/4"), label = label)
        assertTrue(state.mismatch)
        assertFalse(state.canSubmit)
    }

    @Test
    fun matchingUnitCanBeConsumedOnce() {
        val state = ConsumeUiState(expected = UnitRef.parse("31337/7/3"), label = label)
        assertTrue(state.canSubmit)
        assertFalse(state.copy(isSubmitting = true).canSubmit)
        assertFalse(ConsumeUiState(label = null).canSubmit)
    }
}
