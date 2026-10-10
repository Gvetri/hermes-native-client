package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
class EntryScreenSessionFlowsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun populated_and_empty_session_lists_expose_the_explicit_create_action() {
        val events = mutableListOf<EntryUiEvent>()
        val state =
            mutableStateOf(
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
                                        id = SessionId("existing"),
                                        title = "Existing Session",
                                        preview = null,
                                        pinned = false,
                                    ),
                                ),
                        ),
                ),
            )

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state.value, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Create Session").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CreateSessionClicked), events)

        events.clear()
        composeTestRule.runOnIdle {
            state.value = state.value.copy(sessionList = SessionListUiState())
        }
        composeTestRule.onNodeWithText("Create Session").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CreateSessionClicked), events)
    }

    @Test
    fun creation_form_supports_optional_title_confirmation_and_cancellation() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    createSession = SessionCreationUiState(),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Session title (optional)").performTextInput("Draft title")
        composeTestRule.onNodeWithText("Confirm Create Session").assertHasClickAction().performClick()
        assertEquals(
            listOf(
                EntryUiEvent.CreateSessionTitleChanged("Draft title"),
                EntryUiEvent.ConfirmCreateSessionClicked,
            ),
            events,
        )

        events.clear()
        composeTestRule.onNodeWithText("Cancel").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.CancelCreateSessionClicked), events)
    }

    @Test
    fun confirmed_creation_failure_preserves_the_draft_and_exposes_an_explicit_retry() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    createSession =
                                        SessionCreationUiState(
                                            titleDraft = "Preserved title",
                                            errorCategory = SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED,
                                        ),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Preserved title").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.ConfirmCreateSessionClicked), events)
    }

    @Test
    fun creation_pending_state_disables_confirmation_and_cancellation_with_progress() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    createSession =
                                        SessionCreationUiState(
                                            titleDraft = "Draft title",
                                            isSubmitting = true,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Creating Session…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm Create Session").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun loading_stale_and_recoverable_session_states_have_clear_accessible_recovery() {
        val state = mutableStateOf(SessionListUiState(isLoading = true))
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList = state.value,
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Loading Sessions…").assertIsDisplayed()
        composeTestRule.runOnIdle {
            state.value =
                SessionListUiState(
                    sessions =
                        listOf(
                            SessionItemUiState(
                                id = SessionId("stale"),
                                title = "Stale Session",
                                preview = "Previous server preview",
                                pinned = false,
                            ),
                        ),
                    isStale = true,
                    isUnavailable = true,
                    errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                )
        }

        composeTestRule.onNodeWithText(SessionListErrorCategory.GATEWAY_UNAVAILABLE.safeMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(EntryUiEvent.RefreshSessionListClicked, events.single())
    }

    @Test
    fun opened_session_displays_authoritative_history_and_has_a_local_return_action() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    openedSession =
                                        OpenSessionUiState(
                                            session =
                                                SessionItemUiState(
                                                    id = SessionId("session-one"),
                                                    title = "Authoritative title",
                                                    preview = "Authoritative preview",
                                                    pinned = false,
                                                ),
                                            messages =
                                                listOf(
                                                    SessionMessageUiState(
                                                        id = "message-one",
                                                        role = "user",
                                                        content = "Authoritative history",
                                                    ),
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Authoritative title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Authoritative history").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertHasClickAction().performClick()
        assertEquals(EntryUiEvent.ReturnToSessionListClicked, events.single())
    }

    @Test
    fun refresh_is_disabled_while_a_session_is_opening() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                                preview = null,
                                                pinned = false,
                                            ),
                                        ),
                                    openingSessionId = SessionId("session-one"),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Refresh").assertIsNotEnabled()
    }

    @Test
    fun streamed_response_exposes_only_truthful_run_state_and_interruption_semantics() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList =
                                SessionListUiState(
                                    openedSession =
                                        OpenSessionUiState(
                                            session =
                                                SessionItemUiState(
                                                    id = SessionId("session-one"),
                                                    title = "Streaming Session",
                                                    preview = null,
                                                    pinned = false,
                                                ),
                                            messages = emptyList(),
                                            latestRun = Run(RunId("run-one"), SessionId("session-one"), "running"),
                                            latestRunState = RunPresentationState.RUNNING,
                                            activeResponse =
                                                SessionMessageUiState(
                                                    id = "active-response:run-one",
                                                    role = "assistant",
                                                    content = "Partial answer",
                                                    runId = RunId("run-one"),
                                                    isStreaming = true,
                                                    runState = RunPresentationState.RUNNING,
                                                ),
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Partial answer")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Streaming response…")
            .performScrollTo()
            .assertIsDisplayed()
        val runStateNodes = composeTestRule.onAllNodesWithText("Run state: Running")
        runStateNodes.assertCountEquals(2)
        runStateNodes[0].assertIsDisplayed()
        runStateNodes[1].assertIsDisplayed()
        composeTestRule.onNodeWithText("Run status: Running").assertDoesNotExist()
    }
}
