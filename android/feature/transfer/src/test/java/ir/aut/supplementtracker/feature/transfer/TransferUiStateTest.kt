package ir.aut.supplementtracker.feature.transfer

import ir.aut.supplementtracker.core.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferUiStateTest {
    private val segment = Segment(segmentId = "s1", batchId = "7", owner = "0xa", start = 10, end = 60, units = 50, status = "Created")
    private val recipient = "0x" + "ab".repeat(20)

    @Test
    fun emptyCountMeansTheWholeSegment() {
        val state = TransferUiState(segments = listOf(segment), selectedSegmentId = "s1", toAddress = recipient)
        assertEquals(50, state.countValue)
        assertTrue(state.canSubmit)
        assertFalse(state.isPartial)
    }

    @Test
    fun partialCountSplitsTheSegment() {
        val state = TransferUiState(segments = listOf(segment), selectedSegmentId = "s1", count = "20", toAddress = recipient)
        assertTrue(state.countValid)
        assertTrue(state.isPartial)
        assertTrue(state.canSubmit)
    }

    @Test
    fun rejectsOutOfRangeCountsAndBadAddresses() {
        val base = TransferUiState(segments = listOf(segment), selectedSegmentId = "s1", toAddress = recipient)
        assertFalse(base.copy(count = "0").canSubmit)
        assertFalse(base.copy(count = "51").canSubmit)
        assertFalse(base.copy(toAddress = "0x1234").canSubmit)
        assertFalse(base.copy(selectedSegmentId = null).canSubmit)
        assertFalse(base.copy(isSubmitting = true).canSubmit)
    }
}
