package ir.aut.supplementtracker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test
    fun encodesAndParsesStandardPayload() {
        val payload = QrPayload(schemaVersion = 1, productId = "42", chainId = 31337L)
        val parsed = QrPayload.parse(payload.encode())
        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertEquals("42", parsed.productId)
        assertEquals(31337L, parsed.chainId)
    }

    @Test
    fun parsesBareProductIdForCompatibility() {
        val parsed = QrPayload.parse("99")
        assertNotNull(parsed)
        assertEquals("99", parsed!!.productId)
    }

    @Test
    fun rejectsInvalidPayload() {
        assertNull(QrPayload.parse("not-a-payload"))
    }
}
