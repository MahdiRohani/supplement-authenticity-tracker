package ir.aut.supplementtracker.feature.verify

import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.IndependentCheck
import ir.aut.supplementtracker.core.model.RiskAssessment
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import ir.aut.supplementtracker.core.model.VerifiedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyPresentationTest {
    @Test
    fun deviceContradictionDowngradesSellableVerdictsToSuspicious() {
        val broken = IndependentCheck(proofValid = false)
        val wrongRoot = IndependentCheck(proofValid = true, rootMatchesChain = false)
        assertEquals(AuthenticityStatus.Suspicious, unit("Authentic", broken).toAuthenticityStatus())
        assertEquals(AuthenticityStatus.Suspicious, unit("InTransit", wrongRoot).toAuthenticityStatus())
        // Already-bad verdicts keep their more specific meaning.
        assertEquals(AuthenticityStatus.Consumed, unit("Consumed", broken).toAuthenticityStatus())
        assertEquals(AuthenticityStatus.Recalled, unit("Recalled", broken).toAuthenticityStatus())
    }

    @Test
    fun unverifiableChecksDoNotOverrideTheBackend() {
        val skipped = IndependentCheck()
        assertEquals(AuthenticityStatus.Authentic, unit("Authentic", skipped).toAuthenticityStatus())
        assertEquals(AuthenticityStatus.Suspicious, unit("Suspicious", skipped).toAuthenticityStatus())
        assertEquals(AuthenticityStatus.NotFound, unit("Bogus", skipped).toAuthenticityStatus())
    }

    @Test
    fun onlyUncontestedAuthenticUnitsOfferConsumption() {
        val ok = IndependentCheck(proofValid = true, rootMatchesChain = true)
        assertTrue(VerifyUiState(unit = unit("Authentic", ok)).canRecordConsumption)
        assertFalse(VerifyUiState(unit = unit("InTransit", ok)).canRecordConsumption)
        assertFalse(VerifyUiState(unit = unit("Authentic", IndependentCheck(proofValid = false))).canRecordConsumption)
        assertFalse(VerifyUiState(unit = unit("Authentic", ok, consumed = true)).canRecordConsumption)
        assertFalse(VerifyUiState().canRecordConsumption)
    }

    @Test
    fun regionSlugsMatchTheBackendNormalisation() {
        val slug = Regex("^[a-z0-9-]+$")
        assertEquals(31, ScanRegions.size)
        assertEquals(ScanRegions.size, ScanRegions.map { it.slug }.toSet().size)
        ScanRegions.forEach { assertTrue(it.slug, slug.matches(it.slug)) }
        assertEquals("تهران", regionLabel("Tehran", persian = true))
        assertEquals("Isfahan", regionLabel("isfahan", persian = false))
        assertEquals("some-city", regionLabel("some-city", persian = true))
        assertNull(regionLabel(" ", persian = false))
    }

    private fun unit(authenticity: String, check: IndependentCheck, consumed: Boolean = false) =
        VerifiedUnit(
            verification = UnitVerification(
                authenticity = authenticity,
                unit = UnitRef.parse("31337/1/0")!!,
                productName = null,
                lotCode = null,
                manufacturer = null,
                metadataCid = null,
                metadataGatewayUrl = null,
                custodian = null,
                segmentId = null,
                segmentStatus = null,
                consumed = consumed,
                consumption = null,
                recalled = false,
                risk = RiskAssessment(score = 0.0, level = "low", reasons = emptyList()),
                scanCount = 0,
                evidence = null,
                checkedAt = "",
            ),
            check = check,
        )
}
