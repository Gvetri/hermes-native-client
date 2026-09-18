package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class RunStatusNotificationScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settings_control_renders_label_and_switch_with_accessible_description() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedState(runStatusNotifications = RunStatusNotificationsUiState()),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Run status notifications").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Run status notifications")
            .assertIsDisplayed()
            .assertIsOff()
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf(EntryUiEvent.RunStatusNotificationsToggleClicked), events)
    }

    @Test
    fun enabled_state_shows_the_switch_on() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            runStatusNotifications = RunStatusNotificationsUiState(enabled = true),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNode(hasContentDescription("Run status notifications"))
            .assertIsOn()
    }

    @Test
    fun permission_denied_state_shows_a_recoverable_explanation_and_keeps_the_switch_off() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            runStatusNotifications =
                                RunStatusNotificationsUiState(
                                    enabled = false,
                                    explanation = RunStatusNotificationExplanation.PERMISSION_DENIED,
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(RunStatusNotificationExplanation.PERMISSION_DENIED.safeMessage)
            .assertIsDisplayed()
        composeTestRule
            .onNode(hasContentDescription("Run status notifications"))
            .assertIsOff()
    }

    private fun connectedState(runStatusNotifications: RunStatusNotificationsUiState): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "The Gateway contract was verified successfully.",
            actionLabel = "Connected",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    sessions =
                        listOf(
                            SessionItemUiState(
                                id = SessionId("session-one"),
                                title = "Session one",
                                preview = "Preview",
                                pinned = false,
                            ),
                        ),
                ),
            runStatusNotifications = runStatusNotifications,
        )
}
