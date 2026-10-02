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
import org.junit.Assert.assertSame
import org.junit.BeforeClass
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

    companion object {
        @BeforeClass
        @JvmStatic
        fun clearSavedConnection() = clearSavedGatewayConnection()
    }

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
        val activityBeforeRotation = composeTestRule.activity
        val widthBeforeRotation = activityBeforeRotation.resources.configuration.screenWidthDp

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

            assertSame(
                "the shell keeps its activity across a rotation",
                activityBeforeRotation,
                composeTestRule.activity,
            )
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
            // A display rotation settles asynchronously. Wait for the original viewport, so the
            // configuration change cannot leak into the next test's activity in this process.
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    composeTestRule.activity.resources.configuration.screenWidthDp == widthBeforeRotation
                }.getOrDefault(false)
            }
            composeTestRule.waitForIdle()
        }
    }
}
