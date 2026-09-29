package com.suyaphot.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        composeTestRule.onNodeWithText("Create Vault").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore from Backup").assertIsDisplayed()

        // Click create vault and verify credential choice step renders
        composeTestRule.onNodeWithText("Create Vault").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Choose Credential").assertIsDisplayed()
        composeTestRule.onNodeWithText("6-Digit PIN").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pattern (3x3 Grid)").assertIsDisplayed()
    }

    @Test
    fun testRestoreFromBackupButtonOpensRestoreScreen() {
        composeTestRule.onNodeWithText("Restore from Backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore from Backup").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Backup & Restore").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore Archive").assertIsDisplayed()
    }
}
