package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hermesnative.client.feature.entry.application.EntryState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun launch_state_has_meaningful_semantics_and_usable_controls_at_increased_font_scale() {
        val stateHolder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = false),
            )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HermesTheme {
                    EntryScreen(
                        state = stateHolder.uiState.value,
                        onEvent = stateHolder::onEvent,
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText("Connect to a Hermes Gateway")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Add Gateway Connection")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeTestRule.runOnIdle {
            assertTrue(stateHolder.uiState.value.connectionSetupRequested)
        }
    }
}
