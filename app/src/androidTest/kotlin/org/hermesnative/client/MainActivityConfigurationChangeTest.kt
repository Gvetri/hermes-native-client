package org.hermesnative.client

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device configuration-change coverage for the shell: the real composition
 * root survives activity recreation, and a real orientation change keeps the
 * navigation and content regions usable.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityConfigurationChangeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activity_recreation_keeps_the_shell_usable() {
        composeTestRule.onNodeWithText("Connect to a Hermes Gateway").assertIsDisplayed()

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Hermes Native Client").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect to a Hermes Gateway").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Add Gateway Connection")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun orientation_change_keeps_the_shell_usable() {
        composeTestRule.onNodeWithText("Connect to a Hermes Gateway").assertIsDisplayed()

        try {
            composeTestRule.runOnUiThread {
                composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    composeTestRule.activity.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                }.getOrDefault(false)
            }

            composeTestRule.onNodeWithText("Hermes Native Client").assertIsDisplayed()
            composeTestRule.onNodeWithText("Connect to a Hermes Gateway").assertIsDisplayed()
            composeTestRule
                .onNodeWithText("Add Gateway Connection")
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        } finally {
            composeTestRule.runOnUiThread {
                composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            composeTestRule.waitForIdle()
        }
    }
}
