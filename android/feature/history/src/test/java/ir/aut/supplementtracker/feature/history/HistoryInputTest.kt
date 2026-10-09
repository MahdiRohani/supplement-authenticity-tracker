package ir.aut.supplementtracker.feature.history

import ir.aut.supplementtracker.core.model.ScannedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The history screen routes on [ScannedCode]; these cases pin that routing. */
class HistoryInputTest {
    @Test
    fun unitPathAndPublicUrlOpenUnitHistory() {
        assertTrue(ScannedCode.parse("31337/4/2") is ScannedCode.Public)
        assertTrue(
            ScannedCode.parse("https://supplementtracker.aut.ir/u/31337/4/2") is ScannedCode.Public,
        )
        val unit = (ScannedCode.parse("31337/4/2") as ScannedCode.Public).ref
        assertEquals(31337L, unit.chainId)
        assertEquals("4", unit.batchId)
        assertEquals(2L, unit.index)
    }

    @Test
    fun secretQrStillResolvesToTheSameUnit() {
        val key = "a".repeat(64)
        val code = ScannedCode.parse("satk2:31337:4:2:$key")
        assertTrue(code is ScannedCode.Hidden)
        assertEquals("31337/4/2", (code as ScannedCode.Hidden).label.unit.path)
    }

    @Test
    fun legacyProductIdsFallThroughToV1History() {
        assertTrue(ScannedCode.parse("42") is ScannedCode.Legacy)
        assertTrue(ScannedCode.parse("supplementtracker://verify/42") is ScannedCode.Legacy)
        assertEquals("42", (ScannedCode.parse("42") as ScannedCode.Legacy).productId)
    }
}
