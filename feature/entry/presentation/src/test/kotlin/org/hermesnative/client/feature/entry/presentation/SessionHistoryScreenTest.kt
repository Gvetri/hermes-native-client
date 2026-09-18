package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
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
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
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
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Refresh history").assertIsNotEnabled()
    }

    @Test
    fun terminal_reconciliation_shows_stale_progress_and_disables_history_controls_while_retaining_stream() {
        val sessionId = SessionId("session-1")
        val runId = RunId("run-terminal")
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
                                            messages = emptyList(),
                                            composerText = "Next message",
                                            isRefreshing = true,
                                            isStale = true,
                                            latestRun = Run(runId, sessionId, "succeeded"),
                                            latestRunState = RunPresentationState.SUCCEEDED,
                                            activeResponse =
                                                SessionMessageUiState(
                                                    id = "streamed-response",
                                                    role = "assistant",
                                                    content = "Partial response",
                                                    runId = runId,
                                                    runState = RunPresentationState.SUCCEEDED,
                                                    isStreaming = true,
                                                ),
                                            isReconciliationInProgress = true,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Partial response").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refreshing Run and Session state…").assertIsDisplayed()
        composeTestRule.onNodeWithText("The displayed Session history may be stale.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh history").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Message").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun unresolved_submission_shows_uncertainty_without_a_synthetic_latest_run_and_disables_send() {
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
                                            sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                            hasUnresolvedSubmission = true,
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
        composeTestRule.onNodeWithText("Preserved draft").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Latest Run:", substring = true).assertCountEquals(0)
        composeTestRule
            .onNodeWithText(MessageSendErrorCategory.UNCERTAIN.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("The displayed Session history may be stale.").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(SessionHistoryErrorCategory.RECONCILIATION_FAILED.safeMessage)
            .assertExists()
        composeTestRule.onNodeWithText("Refresh history to resolve").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Refresh history").assertIsEnabled()
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
        composeTestRule.onNodeWithText("Send").assertIsEnabled()
        composeTestRule.onNodeWithText("Refresh history").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun mixed_local_and_external_runs_show_gateway_identity_status_result_and_timestamp() {
        val externalRunId = RunId("external-run")
        val localRunId = RunId("local-run")
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
                                            session = SessionItemUiState(SessionId("session-1"), "Shared Session", null, false),
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "external-message",
                                                        role = null,
                                                        content = null,
                                                        runId = externalRunId,
                                                        runStatus = "failed",
                                                        runResult = "Gateway failure",
                                                        timestamp = java.time.Instant.parse("2026-09-08T20:00:00Z"),
                                                    ),
                                                    SessionMessageUiState(
                                                        id = "local-message",
                                                        role = "assistant",
                                                        content = "Local result",
                                                        runId = localRunId,
                                                        runStatus = "succeeded",
                                                        runResult = "Done",
                                                        timestamp = java.time.Instant.parse("2026-09-08T21:00:00Z"),
                                                    ),
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Run ID: external-run").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Failed").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run result: Gateway failure").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Timestamp:", substring = true).assertCountEquals(2)
        composeTestRule.onNodeWithText("Run ID: local-run").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Local result").performScrollTo().assertIsDisplayed()
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

    @Test
    fun failed_run_suppresses_server_failure_text_and_shows_only_the_safe_message() {
        val prompt = "Write a confidential poem"
        val message =
            GatewayHistoryMessage(
                id = "message-failed",
                role = "assistant",
                content = "Partial response text",
                runId = RunId("run-failed"),
                runStatus = "failed",
                runResult = "Provider error: Bearer secret123. Echo: $prompt",
                timestamp = "2026-09-08T20:00:00Z",
            ).toSessionMessageUiState(retryInput = prompt)
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
                                            messages = listOf(message),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Partial response text").assertDoesNotExist()
        composeTestRule.onNodeWithText("Provider error", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Bearer secret123", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Write a confidential poem", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Show technical detail").assertDoesNotExist()
        composeTestRule.onNodeWithText(RunFailureCategory.GATEWAY_REPORTED.safeMessage).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun client_derived_technical_detail_can_be_expanded_as_an_optional_safe_surface() {
        val message =
            SessionMessageUiState(
                id = "message-failed",
                role = "assistant",
                content = null,
                runId = RunId("run-failed"),
                runStatus = "failed",
                failureSafeMessage = RunFailureCategory.GATEWAY_REPORTED.safeMessage,
                failureTechnicalDetail = "The client could not confirm the Run result.",
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
                                            session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
                                            messages = listOf(message),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Show technical detail").performScrollTo().performClick()
        composeTestRule.onNodeWithText("The client could not confirm the Run result.").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide technical detail").performScrollTo().performClick()
        composeTestRule.onNodeWithText("The client could not confirm the Run result.").assertDoesNotExist()
    }

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
                                            session = SessionItemUiState(SessionId("session-1"), "Session", null, false),
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
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Timestamp:", substring = true).assertCountEquals(2)
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeTestRule.onNodeWithText("Run ID: run-failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText(RunFailureCategory.GATEWAY_REPORTED.safeMessage).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Try again").assertCountEquals(1)
        composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        composeTestRule.onNodeWithText("Run ID: run-retried").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry answer").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Completed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run result: Done").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Timestamp:", substring = true).assertCountEquals(2)
    }

    @Test
    fun uncertain_run_has_no_retry_or_failure_detail_until_authoritative_reconciliation_confirms_failure() {
        var entryState by
            mutableStateOf(
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
                ),
            )
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

        entryState =
            entryState.copy(
                sessionList =
                    entryState.sessionList?.copy(
                        openedSession =
                            entryState.sessionList!!.openedSession?.copy(
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
                                                        failureSafeMessage = RunFailureCategory.GATEWAY_REPORTED.safeMessage,
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
}
