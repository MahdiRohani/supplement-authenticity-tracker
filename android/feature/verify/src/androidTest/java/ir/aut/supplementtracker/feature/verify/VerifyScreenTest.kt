package ir.aut.supplementtracker.feature.verify

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.ChainEvidence
import ir.aut.supplementtracker.core.model.IndependentCheck
import ir.aut.supplementtracker.core.model.Party
import ir.aut.supplementtracker.core.model.ProductMetadata
import ir.aut.supplementtracker.core.model.RiskAssessment
import ir.aut.supplementtracker.core.model.RiskReason
import ir.aut.supplementtracker.core.model.UnitRef
import ir.aut.supplementtracker.core.model.UnitVerification
import ir.aut.supplementtracker.core.model.VerifiedUnit
import ir.aut.supplementtracker.core.model.VerifyResult
import org.junit.Rule
import org.junit.Test

class VerifyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsAuthenticState() {
        composeRule.setContent {
            SupplementTheme {
                VerifyScreen(
                    state = VerifyUiState(
                        authenticityStatus = AuthenticityStatus.Authentic,
                        result = VerifyResult(
                            productId = "1",
                            chainProductId = "1",
                            status = "AtPointOfSale",
                            authenticity = "Authentic",
                            currentOwner = "0xabc",
                            metadataCid = "bafy",
                            metadata = ProductMetadata(name = "Vitamin D3", batch = "B-1"),
                            source = "db",
                        ),
                    ),
                    onEvent = {},
                )
            }
        }
        composeRule.onNodeWithTag("verify_status_Authentic").assertIsDisplayed()
        composeRule.onNodeWithText("B-1").assertIsDisplayed()
        composeRule.onNodeWithText("0xabc").assertIsDisplayed()
    }

    @Test
    fun showsConsumedState() {
        composeRule.setContent {
            SupplementTheme {
                VerifyScreen(
                    state = VerifyUiState(
                        authenticityStatus = AuthenticityStatus.Consumed,
                    ),
                    onEvent = {},
                )
            }
        }
        composeRule.onNodeWithTag("verify_status_Consumed").assertIsDisplayed()
    }

    @Test
    fun showsUnitRiskEvidenceAndConsumeAction() {
        val unit = verifiedUnit(check = IndependentCheck(proofValid = true, rootMatchesChain = true, consumedMatchesChain = true))
        composeRule.setContent {
            SupplementTheme {
                VerifyScreen(
                    state = VerifyUiState(unit = unit, authenticityStatus = unit.toAuthenticityStatus()),
                    onEvent = {},
                )
            }
        }
        composeRule.onNodeWithTag("verify_status_Authentic").assertIsDisplayed()
        composeRule.onNodeWithText("Vitamin D3").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_risk_medium").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verify_consume").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verify_check_proof_true").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verify_check_root_true").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun contradictedEvidenceIsSuspiciousAndNotConsumable() {
        val unit = verifiedUnit(check = IndependentCheck(proofValid = false, rootMatchesChain = null))
        composeRule.setContent {
            SupplementTheme {
                VerifyScreen(
                    state = VerifyUiState(unit = unit, authenticityStatus = unit.toAuthenticityStatus()),
                    onEvent = {},
                )
            }
        }
        composeRule.onNodeWithTag("verify_status_Suspicious").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_contradicted").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_check_proof_false").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verify_consume").assertDoesNotExist()
    }

    @Test
    fun warnsWhenTheHiddenCodeIsTypedIntoVerify() {
        composeRule.setContent {
            SupplementTheme {
                VerifyScreen(state = VerifyUiState(hiddenLabelEntered = true), onEvent = {})
            }
        }
        composeRule.onNodeWithTag("verify_hidden_warning").assertIsDisplayed()
    }

    private fun verifiedUnit(check: IndependentCheck): VerifiedUnit =
        VerifiedUnit(
            verification = UnitVerification(
                authenticity = "Authentic",
                unit = UnitRef.parse("31337/7/3")!!,
                productName = "Vitamin D3",
                lotCode = "L-1",
                manufacturer = Party(address = "0x00000000000000000000000000000000000000aa", displayName = "Acme"),
                metadataCid = null,
                metadataGatewayUrl = null,
                custodian = Party(address = "0x00000000000000000000000000000000000000bb", displayName = "Pharmacy 1", region = "tehran"),
                segmentId = "s1",
                segmentStatus = "AtPointOfSale",
                consumed = false,
                consumption = null,
                recalled = false,
                risk = RiskAssessment(
                    score = 0.42,
                    level = "medium",
                    reasons = listOf(RiskReason(code = "foreign_region", weight = 0.42, message = "far away")),
                ),
                scanCount = 5,
                evidence = ChainEvidence(
                    unitKey = "0x00000000000000000000000000000000000000cc",
                    merkleRoot = "0x" + "11".repeat(32),
                    leaf = "0x" + "22".repeat(32),
                    proof = listOf("0x" + "33".repeat(32)),
                    registerTxHash = "0x" + "44".repeat(32),
                ),
                checkedAt = "2026-10-01T10:00:00Z",
            ),
            check = check,
        )

    @Test
    fun showsInvalidNotFoundAndNetworkErrorStates() {
        listOf(
            AuthenticityStatus.Invalid,
            AuthenticityStatus.NotFound,
            AuthenticityStatus.NetworkError,
        ).forEach { status ->
            composeRule.setContent {
                SupplementTheme {
                    VerifyScreen(
                        state = VerifyUiState(authenticityStatus = status),
                        onEvent = {},
                    )
                }
            }
            composeRule.onNodeWithTag("verify_status_${status.name}").assertIsDisplayed()
        }
    }
}
