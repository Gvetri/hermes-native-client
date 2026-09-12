package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMutationScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rename_confirmation_keeps_the_current_title_visible_and_exposes_accessible_actions() {
        val sessionId = SessionId("session-one")
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Current title"),
                            mutation =
                                SessionMutationUiState(
                                    rename = SessionRenameUiState(titleDraft = "Draft title"),
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Current title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rename Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("New Session title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm Rename Session").assertHasClickAction().performClick()
        composeTestRule.onNodeWithText("Cancel Rename").assertHasClickAction().performClick()

        assertEquals(
            listOf(
                EntryUiEvent.ConfirmRenameSessionClicked(sessionId),
                EntryUiEvent.CancelRenameSessionClicked(sessionId),
            ),
            events,
        )
    }

    @Test
    fun delete_confirmation_names_the_session_and_warns_about_irreversible_remote_deletion() {
        val sessionId = SessionId("session-one")
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Remote Session"),
                            mutation = SessionMutationUiState(delete = SessionDeleteUiState()),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNodeWithText("Delete Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete \"Remote Session\"?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Remote deletion cannot be undone by this client.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm Delete Session").assertHasClickAction().performClick()
        composeTestRule.onNodeWithText("Cancel Delete").assertHasClickAction().performClick()

        assertEquals(
            listOf(
                EntryUiEvent.ConfirmDeleteSessionClicked(sessionId),
                EntryUiEvent.CancelDeleteSessionClicked(sessionId),
            ),
            events,
        )
    }

    @Test
    fun rename_validation_feedback_is_visible_and_accessible() {
        val sessionId = SessionId("session-one")
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Current title"),
                            mutation =
                                SessionMutationUiState(
                                    rename =
                                        SessionRenameUiState(
                                            titleDraft = "   ",
                                            errorCategory = SessionRenameErrorCategory.EMPTY_TITLE,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(SessionRenameErrorCategory.EMPTY_TITLE.safeMessage).assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction()
    }

    @Test
    fun failed_rename_exposes_an_accessible_retry_action() {
        val sessionId = SessionId("session-one")
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Current title"),
                            mutation =
                                SessionMutationUiState(
                                    rename =
                                        SessionRenameUiState(
                                            titleDraft = "Draft title",
                                            errorCategory = SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                                        ),
                                    retryAction = SessionMutationAction.RENAME,
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNodeWithText(SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.ConfirmRenameSessionClicked(sessionId)), events)
    }

    @Test
    fun failed_delete_exposes_an_accessible_retry_action() {
        val sessionId = SessionId("session-one")
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Remote Session"),
                            mutation =
                                SessionMutationUiState(
                                    delete = SessionDeleteUiState(),
                                    errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                                    retryAction = SessionMutationAction.DELETE,
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNodeWithText(SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.ConfirmDeleteSessionClicked(sessionId)), events)
    }

    @Test
    fun pending_mutations_disable_controls_for_other_sessions() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            sessions =
                                listOf(
                                    session(SessionId("first"), "First"),
                                    session(SessionId("second"), "Second"),
                                ),
                            mutations =
                                mapOf(
                                    SessionId("first") to
                                        SessionMutationUiState(
                                            pendingAction = SessionMutationAction.PIN,
                                        ),
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Second").assertIsNotEnabled()
    }

    @Test
    fun pending_mutations_disable_duplicate_controls_and_expose_live_feedback() {
        val sessionId = SessionId("session-one")
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Pending Session"),
                            mutation =
                                SessionMutationUiState(
                                    rename = SessionRenameUiState("Draft", isSubmitting = true),
                                    pendingAction = SessionMutationAction.RENAME,
                                ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Renaming Session…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm Rename Session").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel Rename").assertIsNotEnabled()
    }

    @Test
    fun failed_pin_exposes_try_again_without_changing_the_confirmed_session_state() {
        val sessionId = SessionId("session-one")
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        connectedState(
                            session = session(sessionId, "Retry Session"),
                            mutation =
                                SessionMutationUiState(
                                    errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                                    retryAction = SessionMutationAction.PIN,
                                ),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNodeWithText(SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED.safeMessage)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.PinSessionClicked(sessionId)), events)
    }

    private fun connectedState(
        session: SessionItemUiState,
        mutation: SessionMutationUiState,
    ): EntryUiState =
        connectedState(
            sessions = listOf(session),
            mutations = mapOf(session.id to mutation),
        )

    private fun connectedState(
        sessions: List<SessionItemUiState>,
        mutations: Map<SessionId, SessionMutationUiState> = emptyMap(),
    ): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "The Gateway contract was verified successfully.",
            actionLabel = "Connected",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    sessions = sessions,
                    sessionMutations = mutations,
                ),
        )

    private fun session(
        id: SessionId,
        title: String,
        pinned: Boolean = false,
    ): SessionItemUiState =
        SessionItemUiState(
            id = id,
            title = title,
            preview = "Preview",
            pinned = pinned,
        )
}
