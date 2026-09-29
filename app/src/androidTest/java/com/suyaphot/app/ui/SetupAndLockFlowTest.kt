package com.suyaphot.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        // Verify welcome screen branding is displayed
        composeTestRule.onNodeWithText("Suya Phot").assertIsDisplayed()
        composeTestRule.onNodeWithText("A private, encrypted photo and video gallery on your device.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_create_vault_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_restore_backup_btn").assertIsDisplayed()

        // Click create vault and verify credential choice step renders
        composeTestRule.onNodeWithTag("welcome_create_vault_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("credential_choice_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose your vault lock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("credential_pin_option").assertIsDisplayed()
        composeTestRule.onNodeWithTag("credential_pattern_option").assertIsDisplayed()

        // Click PIN option and verify enter PIN renders
        composeTestRule.onNodeWithTag("credential_pin_option").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create Vault PIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a numeric PIN (6 digits)").assertIsDisplayed()
    }

    @Test
    fun testRestoreFromBackupButtonOpensRestoreScreen() {
        composeTestRule.onNodeWithTag("welcome_restore_backup_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_restore_backup_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore Vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("backup_open_file_button").assertIsDisplayed()
    }

    @Test
    fun testSelectPatternNavigatesToCreatePattern() {
        composeTestRule.onNodeWithTag("welcome_create_vault_btn").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("credential_pattern_option").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create Vault Pattern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect at least four dots; six or more is stronger").assertIsDisplayed()
    }
}
