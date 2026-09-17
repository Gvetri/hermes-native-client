package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedVisualRegressionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule()

    @Test
    fun connection_form() {
        setContent(
            EntryUiState(
                title = "Connect to a Gateway",
                supportingText = "Add a Gateway connection to start using Hermes.",
                actionLabel = "Verify connection",
                connectionSetupRequested = true,
                endpoint = "https://gateway.example.test",
                bearerCredential = "fixture-credential",
            ),
        )

        capture()
    }

    @Test
    fun connection_error() {
        setContent(
            EntryUiState(
                title = "Verify a Hermes Gateway",
                supportingText = "Correct the details and try again.",
                actionLabel = "Verify Gateway Connection",
                connectionSetupRequested = true,
                endpoint = "https://gateway.example.test",
                bearerCredential = "fixture-credential",
                errorCategory = EntryErrorCategory.AUTHENTICATION_FAILED,
            ),
        )

        capture()
    }

    @Test
    fun empty_session_list() {
        setContent(
            EntryUiState(
                title = "Gateway connected",
                supportingText = "",
                actionLabel = "",
                sessionList = SessionListUiState(showFirstUseGuidance = true),
            ),
        )

        capture()
    }

    @Test
    fun populated_session_list() {
        setContent(
            connectedState(
                SessionListUiState(
                    sessions =
                        listOf(
                            session(
                                id = "pinned-session",
                                title = "Release checklist",
                                preview = "Review the release checklist.",
                                pinned = true,
                            ),
                            session(
                                id = "follow-up-session",
                                title = "Follow-up Session",
                                preview = "Plan the next step.",
                            ),
                        ),
                ),
            ),
        )

        capture()
    }

    @Test
    fun session_list_unavailable() {
        setContent(
            connectedState(
                SessionListUiState(
                    isStale = true,
                    isUnavailable = true,
                    errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                ),
            ),
        )

        capture()
    }

    @Test
    fun create_session() {
        setContent(
            connectedState(
                SessionListUiState(
                    createSession = SessionCreationUiState(titleDraft = "Release checklist"),
                ),
            ),
        )

        capture()
    }

    @Test
    fun rename_session_confirmation() {
        val sessionId = SessionId("rename-session")
        setContent(
            connectedState(
                SessionListUiState(
                    sessions = listOf(session("rename-session", "Current title", "Current preview")),
                    sessionMutations =
                        mapOf(
                            sessionId to
                                SessionMutationUiState(
                                    rename = SessionRenameUiState(titleDraft = "Updated title"),
                                ),
                        ),
                ),
            ),
        )

        capture()
    }

    @Test
    fun delete_session_confirmation() {
        val sessionId = SessionId("delete-session")
        setContent(
            connectedState(
                SessionListUiState(
                    sessions = listOf(session("delete-session", "Remote Session", "Remote preview")),
                    sessionMutations =
                        mapOf(
                            sessionId to
                                SessionMutationUiState(
                                    delete = SessionDeleteUiState(),
                                ),
                        ),
                ),
            ),
        )

        capture()
    }

    @Test
    fun empty_session_detail() {
        setContent(
            connectedState(
                SessionListUiState(
                    openedSession =
                        OpenSessionUiState(
                            session = session("empty-session", "New Session"),
                            messages = emptyList(),
                        ),
                ),
            ),
        )

        capture()
    }

    @Test
    fun session_detail_with_messages() {
        setContent(
            EntryUiState(
                title = "Gateway connected",
                supportingText = "",
                actionLabel = "",
                sessionList =
                    SessionListUiState(
                        openedSession =
                            OpenSessionUiState(
                                session =
                                    SessionItemUiState(
                                        id = SessionId("fixture-session"),
                                        title = "Release checklist",
                                        preview = "Review the release checklist.",
                                        pinned = true,
                                    ),
                                messages =
                                    listOf(
                                        SessionMessageUiState(
                                            id = "message-1",
                                            role = "user",
                                            content = "Show the release checklist.",
                                        ),
                                        SessionMessageUiState(
                                            id = "message-2",
                                            role = "assistant",
                                            content = "The release checklist is ready.",
                                        ),
                                    ),
                                composerText = "Summarize the next step",
                            ),
                    ),
            ),
        )

        capture()
    }

    @Test
    fun session_detail_active_run() {
        val sessionId = SessionId("active-session")
        val runId = RunId("active-run")
        setContent(
            connectedState(
                SessionListUiState(
                    openedSession =
                        OpenSessionUiState(
                            session = session("active-session", "Active Session"),
                            messages = emptyList(),
                            composerText = "Follow-up question",
                            latestRun = Run(runId, sessionId, "running"),
                            latestRunState = RunPresentationState.RUNNING,
                            activeResponse =
                                SessionMessageUiState(
                                    id = "active-response",
                                    role = "assistant",
                                    content = "Partial response",
                                    runId = runId,
                                    runState = RunPresentationState.RUNNING,
                                    isStreaming = true,
                                ),
                        ),
                ),
            ),
        )

        capture()
    }

    @Test
    fun session_detail_error() {
        setContent(
            connectedState(
                SessionListUiState(
                    openedSession =
                        OpenSessionUiState(
                            session = session("failed-session", "Retry Session"),
                            messages = emptyList(),
                            composerText = "Keep this draft",
                            sendErrorCategory = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED,
                        ),
                ),
            ),
        )

        capture()
    }

    private fun setContent(state: EntryUiState) {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = {})
            }
        }
    }

    private fun connectedState(sessionList: SessionListUiState): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "",
            actionLabel = "",
            sessionList = sessionList,
        )

    private fun session(
        id: String,
        title: String,
        preview: String? = null,
        pinned: Boolean = false,
    ): SessionItemUiState =
        SessionItemUiState(
            id = SessionId(id),
            title = title,
            preview = preview,
            pinned = pinned,
        )

    private fun capture() {
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage()
    }
}
