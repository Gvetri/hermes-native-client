package org.hermesnative.client

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

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
}
