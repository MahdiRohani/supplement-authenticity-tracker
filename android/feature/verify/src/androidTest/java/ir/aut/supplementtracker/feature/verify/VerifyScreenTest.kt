package ir.aut.supplementtracker.feature.verify

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.model.ProductMetadata
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
        composeRule.onNodeWithText("Name: Vitamin D3").assertIsDisplayed()
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
