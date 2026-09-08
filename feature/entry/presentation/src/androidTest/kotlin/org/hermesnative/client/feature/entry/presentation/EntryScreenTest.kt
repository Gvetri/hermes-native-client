package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.junit.Assert.assertEquals
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

    @Test
    fun connection_form_has_accessible_fields_and_requires_explicit_verification() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            EntryUiState(
                title = "Verify a Hermes Gateway",
                supportingText = "Enter one profile-specific HTTPS endpoint and bearer credential.",
                actionLabel = "Verify Gateway Connection",
                connectionSetupRequested = true,
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Gateway HTTPS endpoint").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bearer credential").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Verify Gateway Connection")
            .assertHasClickAction()
            .performClick()

        assertEquals(EntryUiEvent.VerifyGatewayConnectionClicked, events.single())
    }

    @Test
    fun loading_state_and_each_error_category_offer_clear_recovery() {
        val loadingState =
            EntryUiState(
                title = "Verify a Hermes Gateway",
                supportingText = "Checking the Gateway.",
                actionLabel = "Verify Gateway Connection",
                connectionSetupRequested = true,
                isVerifying = true,
            )
        val state = mutableStateOf(loadingState)
        val events = mutableListOf<EntryUiEvent>()

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = state.value,
                    onEvent = events::add,
                )
            }
        }
        composeTestRule.onNodeWithText("Verifying Gateway connection…").assertIsDisplayed()

        GatewayErrorCategory.entries.forEach { category ->
            events.clear()
            composeTestRule.runOnIdle {
                state.value =
                    EntryUiState(
                        title = "Verify a Hermes Gateway",
                        supportingText = "Correct the details and try again.",
                        actionLabel = "Verify Gateway Connection",
                        connectionSetupRequested = true,
                        errorCategory = category,
                    )
            }

            composeTestRule.onNodeWithText(category.safeMessage).assertIsDisplayed()
            composeTestRule.onNodeWithText("Try again").performClick()
            assertEquals(EntryUiEvent.TryAgainClicked, events.single())
        }
    }

    @Test
    fun connected_state_is_rendered_without_a_second_connection_action() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            connectionSetupRequested = true,
                            isConnected = true,
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Connected to Gateway").assertIsDisplayed()
    }
}
