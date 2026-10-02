package org.hermesnative.client

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level configuration coverage for a connected shell: the host activity
 * declares the orientation change, so a real rotation keeps one activity
 * instance with the open conversation, its composer and Send still usable.
 */
@RunWith(AndroidJUnit4::class)
class ShellHostRotationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ShellHostActivity>()

    @Test
    fun open_conversation_stays_usable_after_an_orientation_change() {
        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed()
        val activityBeforeRotation = composeTestRule.activity
        val widthBeforeRotation = activityBeforeRotation.resources.configuration.screenWidthDp

        try {
            composeTestRule.runOnUiThread {
                activityBeforeRotation.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
            assertTrue(
                "the rotation really changed the window width",
                composeTestRule.activity.resources.configuration.screenWidthDp != widthBeforeRotation,
            )
            composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed()
            composeTestRule.onNodeWithText("Message").assertIsDisplayed()
            composeTestRule.onNodeWithText("Send").assertIsDisplayed()
        } finally {
            composeTestRule.runOnUiThread {
                composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            composeTestRule.waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    composeTestRule.activity.resources.configuration.screenWidthDp == widthBeforeRotation
                }.getOrDefault(false)
            }
            composeTestRule.waitForIdle()
        }
    }
}
