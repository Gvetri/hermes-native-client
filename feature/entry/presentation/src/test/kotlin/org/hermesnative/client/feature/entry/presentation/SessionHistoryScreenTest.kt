package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionHistoryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun populated_history_shows_run_result_status_timestamp_and_usable_composer() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-1",
                                                        role = "user",
                                                        content = "Run this",
                                                        runId = RunId("run-1"),
                                                        runStatus = "completed",
                                                        runResult = "Done",
                                                        timestamp = java.time.Instant.parse("2026-09-08T20:00:00Z"),
                                                    ),
                                                    SessionMessageUiState(
                                                        id = "message-2",
                                                        role = "assistant",
                                                        content = "Answer",
                                                    ),
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Run this").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Role: user").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run result: Done").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Completed").performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(hasText("Timestamp:", substring = true)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeTestRule.onNodeWithText("Answer").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Role: assistant").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Message").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Refresh history").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun refreshing_history_preserves_content_and_shows_refreshing_state() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-1",
                                                        role = "user",
                                                        content = "Preserved content",
                                                    ),
                                                ),
                                            composerText = "Preserved draft",
                                            isRefreshing = true,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Preserved content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Preserved draft").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refreshing Session history…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh history").assertIsNotEnabled()
    }

    @Test
    fun uncertain_run_preserves_temporary_content_and_disables_send() {
        val runId = RunId("run-1")
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-1",
                                                        role = "user",
                                                        content = "Preserved content",
                                                    ),
                                                ),
                                            composerText = "Preserved draft",
                                            latestRun = Run(runId, SessionId("session-1"), "running"),
                                            latestRunState = RunPresentationState.UNCERTAIN,
                                            activeResponse =
                                                SessionMessageUiState(
                                                    id = "active-response:run-1",
                                                    role = "assistant",
                                                    content = "Partial response",
                                                    runId = runId,
                                                    runState = RunPresentationState.UNCERTAIN,
                                                    streamInterrupted = true,
                                                ),
                                            isStale = true,
                                            errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Preserved content").assertExists()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeTestRule.onNodeWithText("Partial response").assertExists()
        composeTestRule.onNodeWithText("Preserved draft").assertIsDisplayed()
        val uncertainRunStateNodes = composeTestRule.onAllNodesWithText("Run state: Uncertain")
        uncertainRunStateNodes.assertCountEquals(2)
        uncertainRunStateNodes[0].assertIsDisplayed()
        uncertainRunStateNodes[1].assertExists()
        composeTestRule.onNodeWithText("The displayed Session history may be stale.").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(SessionHistoryErrorCategory.RECONCILIATION_FAILED.safeMessage)
            .assertExists()
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun list_refresh_disables_history_refresh() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "Connected",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    isRefreshing = true,
                                    openedSession =
                                        OpenSessionUiState(
                                            session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                                            messages = emptyList(),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Refresh history").assertIsNotEnabled()
    }

    @Test
    fun failed_history_preserves_stale_content_and_keeps_refresh_available() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-1",
                                                        role = "user",
                                                        content = "Preserved content",
                                                    ),
                                                ),
                                            composerText = "Preserved draft",
                                            isStale = true,
                                            errorCategory = SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Preserved content")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Preserved draft").assertIsDisplayed()
        composeTestRule.onNodeWithText("The displayed Session history may be stale.").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh history").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun empty_history_shows_guidance_and_composer() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "Connected",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    openedSession =
                                        OpenSessionUiState(
                                            session = SessionItemUiState(SessionId("session-1"), "Empty", null, false),
                                            messages = emptyList(),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No messages in this Session.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message").assertIsDisplayed().assertIsEnabled()
    }
}
