package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class MessageComposerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun explicit_send_emits_only_the_send_intent_and_shows_the_current_run() {
        val events = mutableListOf<EntryUiEvent>()
        val run = Run(RunId("run-1"), SessionId("session-1"), "succeeded")
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedState(composerText = "Run this", latestRun = run),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Latest Run: run-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Completed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send").assertHasClickAction().assertIsEnabled().performClick()

        assertEquals(listOf(EntryUiEvent.SendMessageClicked), events)
    }

    @Test
    fun focused_keyboard_send_emits_the_same_explicit_send_intent() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = connectedState(composerText = "Run this"),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Message").performImeAction()

        assertEquals(listOf(EntryUiEvent.SendMessageClicked), events)
    }

    @Test
    fun active_run_keeps_the_composer_editable_but_disables_send() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            composerText = "Draft",
                            latestRun = Run(RunId("run-1"), SessionId("session-1"), "running"),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Message").assertIsEnabled()
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun confirmed_failure_shows_the_safe_error_and_keeps_try_again_explicit() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            composerText = "Keep this draft",
                            sendErrorCategory = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED,
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Keep this draft").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(MessageSendErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertIsEnabled().performClick()

        assertEquals(listOf(EntryUiEvent.SendMessageClicked), events)
    }

    private fun connectedState(
        composerText: String = "",
        latestRun: Run? = null,
        sendErrorCategory: MessageSendErrorCategory? = null,
    ): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "Connected",
            actionLabel = "Connected",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    openedSession =
                        OpenSessionUiState(
                            session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                            messages = emptyList(),
                            composerText = composerText,
                            latestRun = latestRun,
                            sendErrorCategory = sendErrorCategory,
                        ),
                ),
        )
}
