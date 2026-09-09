package ir.aut.supplementtracker.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.aut.supplementtracker.core.designsystem.components.AuthenticityStatus
import ir.aut.supplementtracker.core.designsystem.components.StatusChip
import ir.aut.supplementtracker.core.designsystem.components.SupplementButton
import ir.aut.supplementtracker.core.designsystem.components.SupplementTextField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignSystemGoldenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun statusChipGoldenStates() {
        AuthenticityStatus.entries.forEach { status ->
            composeRule.setContent {
                SupplementTheme {
                    StatusChip(
                        status = status,
                        modifier = Modifier.testTag("golden_${status.name}"),
                    )
                }
            }
            composeRule.onNodeWithTag("golden_${status.name}").assertIsDisplayed()
        }
    }

    @Test
    fun buttonAndFieldGolden() {
        composeRule.setContent {
            SupplementTheme {
                SupplementButton(
                    text = "Continue",
                    onClick = {},
                    modifier = Modifier.testTag("golden_button"),
                )
                SupplementTextField(
                    value = "sample",
                    onValueChange = {},
                    label = "Label",
                    modifier = Modifier.testTag("golden_field"),
                )
            }
        }
        composeRule.onNodeWithTag("golden_button").assertIsDisplayed()
        composeRule.onNodeWithTag("golden_field").assertIsDisplayed()
    }
}
