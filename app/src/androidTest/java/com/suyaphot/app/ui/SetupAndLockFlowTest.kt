package com.suyaphot.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suyaphot.app.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupAndLockFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testWelcomeScreenRendersAndNavigatesToCreateVault() {
        // If not on welcome screen, go back
        try {
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}

        // Verify welcome screen branding is displayed
        composeTestRule.onNodeWithText("Suya Phot").assertIsDisplayed()
        composeTestRule.onNodeWithText("A private, encrypted photo and video gallery on your device.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_create_vault_btn", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_restore_backup_btn", useUnmergedTree = true).assertIsDisplayed()

        // Click create vault and verify credential choice step renders
        composeTestRule.onNodeWithTag("welcome_create_vault_btn", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("credential_choice_screen", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose your vault lock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("credential_pin_option", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("credential_pattern_option", useUnmergedTree = true).assertIsDisplayed()

        // Click PIN option and verify enter PIN renders
        composeTestRule.onNodeWithTag("credential_pin_option", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create Vault PIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a numeric PIN (6 digits)").assertIsDisplayed()
    }

    @Test
    fun testSecureSetupStateRestartsAfterActivityRecreation() {
        try {
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}

        composeTestRule.onNodeWithTag("welcome_create_vault_btn", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("credential_choice_screen", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("welcome_create_vault_btn", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Suya Phot").assertIsDisplayed()
    }

    @Test
    fun testRestoreFromBackupButtonOpensRestoreScreen() {
        // If not on welcome screen, go back
        try {
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}

        composeTestRule.onNodeWithTag("welcome_restore_backup_btn", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_restore_backup_btn", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore Vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("backup_open_file_button", useUnmergedTree = true).assertIsDisplayed()

        // Clean up: return to welcome screen so subsequent tests find welcome nodes
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testSelectPatternNavigatesToCreatePattern() {
        // If on restore screen, go back
        try {
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}

        // Navigate to credential choice if on welcome screen
        try {
            composeTestRule.onNodeWithTag("welcome_create_vault_btn", useUnmergedTree = true).performClick()
            composeTestRule.waitForIdle()
        } catch (_: Throwable) {}

        composeTestRule.onNodeWithTag("credential_pattern_option", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create Vault Pattern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect at least four dots; six or more is stronger").assertIsDisplayed()
    }
}
