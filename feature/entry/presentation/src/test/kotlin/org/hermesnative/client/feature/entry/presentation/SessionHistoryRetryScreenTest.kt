package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionHistoryRetryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun failed_run_try_again_dispatches_a_user_initiated_retry_event() {
        val failedRunId = RunId("run-failed")
        val events = mutableListOf<EntryUiEvent>()
        val message =
            SessionMessageUiState(
                id = "message-failed",
                role = "user",
                content = "Original request",
                runId = failedRunId,
                runResult = "Remote failure",
                timestamp = java.time.Instant.parse("2026-09-08T20:00:00Z"),
                failureSafeMessage = RunFailureCategory.GATEWAY_REPORTED.safeMessage,
                retryAvailable = true,
            )
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
                                            session =
                                                SessionItemUiState(SessionId("session-1"), "Session", null, false),
                                            messages = listOf(message),
                                        ),
                                ),
                        ),
                    onEvent = { events += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(listOf<EntryUiEvent>(EntryUiEvent.RetryRunClicked(failedRunId)), events)
    }

    @Test
    fun retry_run_is_shown_independently_with_its_own_identity_and_the_failed_run_stays_visible() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = retriedRunHistoryState(), onEvent = {})
            }
        }

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeTestRule.onAllNodesWithText("Timestamp:", substring = true).assertCountEquals(3)
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        composeTestRule.onNodeWithText("Run ID: run-failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText(RunFailureCategory.GATEWAY_REPORTED.safeMessage).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Try again").assertCountEquals(1)
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        composeTestRule.onNodeWithText("Run ID: run-retried").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry answer").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Completed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run result: Done").assertIsDisplayed()
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        composeTestRule.onNodeWithText("Run ID: run-failed").assertIsDisplayed()
    }

    @Test
    fun uncertain_run_has_no_retry_or_failure_detail_until_authoritative_reconciliation_confirms_failure() {
        var entryState by mutableStateOf(uncertainRunHistoryState())
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = entryState, onEvent = {})
            }
        }

        composeTestRule.onNodeWithText("Run state: Uncertain").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stream interrupted. Run result is uncertain.").assertIsDisplayed()
        composeTestRule.onNodeWithText(RunFailureCategory.GATEWAY_REPORTED.safeMessage).assertDoesNotExist()
        composeTestRule.onNodeWithText("Try again").assertDoesNotExist()
        composeTestRule.onNodeWithText("Show technical detail").assertDoesNotExist()

        entryState = confirmedFailureHistoryState(entryState)

        composeTestRule.onNodeWithText("Run state: Uncertain").assertDoesNotExist()
        composeTestRule.onNodeWithText("Run status: Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText(RunFailureCategory.GATEWAY_REPORTED.safeMessage).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Try again").assertCountEquals(1)
    }

    @Test
    fun retry_button_is_disabled_while_an_active_run_is_in_flight() {
        val sessionId = SessionId("session-1")
        val activeRun = Run(RunId("run-active"), sessionId, "running")
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
                                            session = SessionItemUiState(sessionId, "Session", null, false),
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-failed",
                                                        role = "assistant",
                                                        content = null,
                                                        runId = RunId("run-failed"),
                                                        runStatus = "failed",
                                                        failureSafeMessage =
                                                            RunFailureCategory.GATEWAY_REPORTED.safeMessage,
                                                        retryAvailable = true,
                                                    ),
                                                ),
                                            activeRuns = listOf(activeRun),
                                            latestRun = activeRun,
                                            latestRunState = RunPresentationState.RUNNING,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Try again").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun opening_a_session_lands_on_its_newest_transcript_message() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = transcriptEntryState(messages = (1..12).map(::transcriptMessage)),
                    onEvent = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transcript message 12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transcript message 1").assertDoesNotExist()
    }

    @Test
    fun an_arriving_message_is_followed_while_the_newest_content_is_shown() {
        var state by mutableStateOf(transcriptEntryState(messages = (1..10).map(::transcriptMessage)))
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = {})
            }
        }
        composeTestRule.waitForIdle()

        state = transcriptEntryState(messages = (1..11).map(::transcriptMessage))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transcript message 11").assertIsDisplayed()
    }

    @Test
    fun an_arriving_message_does_not_pull_a_reader_who_scrolled_away_and_follow_resumes_at_the_newest_content() {
        var state by mutableStateOf(transcriptEntryState(messages = (1..10).map(::transcriptMessage)))
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = {})
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        composeTestRule.waitForIdle()

        state = transcriptEntryState(messages = (1..11).map(::transcriptMessage))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transcript message 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transcript message 11").assertDoesNotExist()

        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(11)
        composeTestRule.waitForIdle()

        state = transcriptEntryState(messages = (1..12).map(::transcriptMessage))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Transcript message 12").assertIsDisplayed()
    }

    private fun transcriptEntryState(messages: List<SessionMessageUiState>): EntryUiState =
        entryState(
            SessionListUiState(
                openedSession =
                    OpenSessionUiState(
                        session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                        messages = messages,
                    ),
            ),
        )

    private fun transcriptMessage(index: Int): SessionMessageUiState =
        message(
            "message-$index",
            "user",
            "Transcript message $index",
        )

    private fun retriedRunHistoryState(): EntryUiState =
        entryState(
            SessionListUiState(
                openedSession =
                    OpenSessionUiState(
                        session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                        messages =
                            listOf(
                                SessionMessageUiState(
                                    id = "original-user-message",
                                    role = "user",
                                    content = "Original request",
                                    timestamp = java.time.Instant.parse("2026-09-08T20:00:00Z"),
                                ),
                                SessionMessageUiState(
                                    id = "message-failed",
                                    role = "assistant",
                                    content = null,
                                    runId = RunId("run-failed"),
                                    runStatus = "failed",
                                    runResult = "Remote failure",
                                    timestamp = java.time.Instant.parse("2026-09-08T20:01:00Z"),
                                    failureSafeMessage = RunFailureCategory.GATEWAY_REPORTED.safeMessage,
                                    retryAvailable = true,
                                ),
                                SessionMessageUiState(
                                    id = "message-retried",
                                    role = "assistant",
                                    content = "Retry answer",
                                    runId = RunId("run-retried"),
                                    runStatus = "succeeded",
                                    runResult = "Done",
                                    timestamp = java.time.Instant.parse("2026-09-08T20:10:00Z"),
                                ),
                            ),
                    ),
            ),
        )

    private fun uncertainRunHistoryState(): EntryUiState =
        entryState(
            SessionListUiState(
                openedSession =
                    OpenSessionUiState(
                        session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                        messages =
                            listOf(
                                SessionMessageUiState(
                                    id = "message-interrupted",
                                    role = "assistant",
                                    content = "Partial answer",
                                    runId = RunId("run-interrupted"),
                                    runState = RunPresentationState.UNCERTAIN,
                                    streamInterrupted = true,
                                    timestamp = java.time.Instant.parse("2026-09-08T20:00:00Z"),
                                ),
                            ),
                    ),
            ),
        )

    private fun confirmedFailureHistoryState(state: EntryUiState): EntryUiState =
        state.copy(
            sessionList =
                state.sessionList?.copy(
                    openedSession =
                        state.sessionList!!.openedSession?.copy(
                            messages =
                                listOf(
                                    SessionMessageUiState(
                                        id = "message-confirmed-failed",
                                        role = "assistant",
                                        content = null,
                                        runId = RunId("run-interrupted"),
                                        runStatus = "failed",
                                        runResult = "Remote failure",
                                        timestamp = java.time.Instant.parse("2026-09-08T20:01:00Z"),
                                        failureSafeMessage = RunFailureCategory.GATEWAY_REPORTED.safeMessage,
                                        retryAvailable = true,
                                    ),
                                ),
                        ),
                ),
        )
}
