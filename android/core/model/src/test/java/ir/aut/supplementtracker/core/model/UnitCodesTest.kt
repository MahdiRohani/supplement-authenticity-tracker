package ir.aut.supplementtracker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitCodesTest {
    private val key = "e6b32d5f7d9b973cae55df813ef956024bc1116f50ff2ca4cf88c943ca4ac4b3"

    @Test
    fun parsesPublicLabelFromAnyVerifyHost() {
        val expected = UnitRef(31337, "7", 42)
        assertEquals(expected, UnitRef.parse("https://supplementtracker.aut.ir/u/31337/7/42"))
        assertEquals(expected, UnitRef.parse("http://10.0.2.2:3000/u/31337/7/42/?utm=x#top"))
        assertEquals(expected, UnitRef.parse("  31337/7/42 "))
        assertEquals("31337/7/42", expected.path)
        assertEquals("https://x.ir/u/31337/7/42", expected.publicUrl("https://x.ir/u/"))
    }

    @Test
    fun rejectsMalformedPublicLabels() {
        listOf(
            "",
            "31337/7",
            "a/b/c",
            "0/7/1",
            "31337/0/1",
            "31337/7/4294967296",
            "31337/7/-1",
            "1/2/3/4",
            "https://supplementtracker.aut.ir/verify/12",
        ).forEach { assertNull(it, UnitRef.parse(it)) }
    }

    @Test
    fun parsesHiddenLabelAndRedactsTheKey() {
        val label = SecretLabel.parse("satk2:31337:7:42:0x${key.uppercase()}")!!
        assertEquals(UnitRef(31337, "7", 42), label.unit)
        assertEquals(key, label.privateKeyHex)
        assertFalse(label.toString().contains(key))
        assertEquals(label, SecretLabel.parse("satk2:31337:7:42:$key"))
    }

    @Test
    fun rejectsMalformedHiddenLabels() {
        listOf(
            "satk1:31337:7:42:$key",
            "satk2:31337:7:42",
            "satk2:31337:7:42:${key.dropLast(2)}",
            "satk2:31337:7:42:${"0".repeat(64)}",
            "satk2:31337:x:42:$key",
            "satk2:31337:7:42:${key}zz",
        ).forEach { assertNull(it, SecretLabel.parse(it)) }
    }

    @Test
    fun classifiesEveryLabelGeneration() {
        assertEquals(
            ScannedCode.Public(UnitRef(1, "2", 3)),
            ScannedCode.parse("https://supplementtracker.aut.ir/u/1/2/3"),
        )
        assertTrue(ScannedCode.parse("satk2:1:2:3:$key") is ScannedCode.Hidden)
        assertEquals(ScannedCode.Legacy("42"), ScannedCode.parse("""{"v":1,"productId":"42","chainId":31337}"""))
        assertEquals(ScannedCode.Legacy("42"), ScannedCode.parse("42"))
        assertEquals(ScannedCode.Legacy("12"), ScannedCode.parse("https://supplementtracker.aut.ir/verify/12"))
        assertEquals(ScannedCode.Legacy("12"), ScannedCode.parse("supplementtracker://verify/12"))
        assertNull(ScannedCode.parse("hello"))
    }

    @Test
    fun credentialsNeverPrintSecretQr() {
        val credential = UnitCredential(0, "0xabc", "https://x/u/1/2/0", "satk2:1:2:0:$key")
        assertFalse(credential.toString().contains(key))
    }
}
