package org.hermesnative.client

import android.view.WindowManager
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @BeforeClass
        @JvmStatic
        fun clearSavedConnection() = clearSavedGatewayConnection()
    }

    @Test
    fun launch_state_is_accessible_and_offers_gateway_connection_action() {
        composeTestRule
            .onNodeWithText("Connect to a Hermes Gateway")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Add Gateway Connection")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun window_delivers_keyboard_insets_without_panning_the_compose_screen() {
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            composeTestRule.activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }
}
