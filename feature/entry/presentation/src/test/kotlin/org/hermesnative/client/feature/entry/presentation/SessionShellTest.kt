package org.hermesnative.client.feature.entry.presentation

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionShellTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun phone_shell_replaces_the_session_list_with_the_conversation() {
        setShellContent(
            sessionList =
                SessionListUiState(
                    sessions = listOf(session("first", "First Session")),
                    openedSession =
                        OpenSessionUiState(
                            session = session("first", "First Session"),
                            messages = listOf(message("message-1", "user", "Conversation message")),
                        ),
                ),
        )

        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sessions").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("First Session").assertCountEquals(1)
        composeTestRule.onNodeWithText("Conversation message").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun two_pane_shell_shows_the_session_list_and_conversation_together() {
        setShellContent(
            sessionList =
                SessionListUiState(
                    sessions = listOf(session("first", "First Session"), session("second", "Second Session")),
                    openedSession =
                        OpenSessionUiState(
                            session = session("first", "First Session"),
                            messages = listOf(message("message-1", "assistant", "Conversation message")),
                        ),
                ),
        )

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second Session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Conversation message").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("First Session").assertCountEquals(2)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun two_pane_shell_shows_guidance_until_a_session_is_selected() {
        setShellContent(
            sessionList =
                SessionListUiState(
                    sessions = listOf(session("first", "First Session")),
                ),
        )

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Session selected").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Select a Session from the list to view its conversation.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun two_pane_shell_marks_the_open_session_as_selected() {
        setShellContent(
            sessionList =
                SessionListUiState(
                    sessions = listOf(session("first", "First Session"), session("second", "Second Session")),
                    openedSession = OpenSessionUiState(session = session("first", "First Session"), messages = emptyList()),
                ),
        )

        composeTestRule
            .onNode(hasText("First Session") and hasClickAction())
            .assertIsSelected()
        composeTestRule
            .onNode(hasText("Second Session") and hasClickAction())
            .assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun shell_renders_loading_empty_and_unavailable_states_in_the_list_pane() {
        val state = mutableStateOf(entryState(SessionListUiState(isLoading = true)))
        setShellContent(state)

        composeTestRule.onNodeWithText("Loading Sessions…").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Session selected").assertIsDisplayed()

        composeTestRule.runOnIdle { state.value = entryState(SessionListUiState()) }
        composeTestRule.onNodeWithText("No Sessions on this Gateway").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value = entryState(SessionListUiState(isStale = true, isUnavailable = true))
        }
        composeTestRule.onNodeWithText("Sessions are unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "The displayed Session data may be stale. Gateway actions are unavailable until the connection recovers.",
        )
            .assertIsDisplayed()
    }

    @Test
    fun phone_conversation_reflows_and_stays_usable_at_increased_font_scale() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HermesTheme {
                    EntryScreen(state = conversationEntryState(), onEvent = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed().assertHasClickAction()
        composeTestRule.onNodeWithText("Message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send").assertIsDisplayed().assertHasClickAction()
        composeTestRule.onNodeWithText("Conversation message").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun two_pane_shell_stays_usable_at_increased_font_scale() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                HermesTheme {
                    EntryScreen(state = conversationEntryState(), onEvent = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed().assertHasClickAction()
        composeTestRule.onNodeWithText("Send").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun shell_controls_have_click_actions_and_minimum_touch_targets() {
        val state = mutableStateOf(conversationEntryState())
        setShellContent(state)

        composeTestRule.onNodeWithText("Back to Sessions").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Send").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Refresh history").assertHasClickAction().assertHeightIsAtLeast(48.dp)

        composeTestRule.runOnIdle {
            state.value =
                entryState(
                    SessionListUiState(sessions = listOf(session("first", "First Session"))),
                )
        }
        composeTestRule
            .onNodeWithText("Create Session")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule
            .onNodeWithText("First Session")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp")
    fun short_landscape_window_keeps_the_session_list_reachable() {
        setShellContent(
            SessionListUiState(sessions = listOf(session("first", "First Session"))),
        )

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-list").performScrollToIndex(1)

        composeTestRule.onNodeWithText("First Session").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w599dp-h891dp")
    fun compact_width_below_the_boundary_stays_single_pane() {
        setShellContent(sessionList = SessionListUiState(sessions = listOf(session("first", "First Session"))))

        composeTestRule.onNodeWithText("No Session selected").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w600dp-h891dp")
    fun width_at_the_boundary_uses_the_two_pane_layout() {
        setShellContent(sessionList = SessionListUiState(sessions = listOf(session("first", "First Session"))))

        composeTestRule.onNodeWithText("No Session selected").assertIsDisplayed()
    }

    @Test
    fun width_configuration_change_switches_between_single_pane_and_two_pane() {
        val configuration = mutableStateOf(phoneConfiguration())
        composeTestRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                HermesTheme {
                    EntryScreen(
                        state = entryState(SessionListUiState(sessions = listOf(session("first", "First Session")))),
                        onEvent = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("No Session selected").assertDoesNotExist()

        composeTestRule.runOnIdle { configuration.value = wideConfiguration() }
        composeTestRule.onNodeWithText("No Session selected").assertIsDisplayed()

        composeTestRule.runOnIdle { configuration.value = phoneConfiguration() }
        composeTestRule.onNodeWithText("No Session selected").assertDoesNotExist()
    }

    private fun phoneConfiguration(): Configuration =
        Configuration().apply {
            screenWidthDp = 411
            screenHeightDp = 891
        }

    private fun wideConfiguration(): Configuration =
        Configuration().apply {
            screenWidthDp = 1000
            screenHeightDp = 800
        }

    private fun setShellContent(sessionList: SessionListUiState) {
        setShellContent(mutableStateOf(entryState(sessionList)))
    }

    private fun setShellContent(state: MutableState<EntryUiState>) {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state.value, onEvent = {})
            }
        }
    }

    private fun conversationEntryState(): EntryUiState =
        entryState(
            SessionListUiState(
                sessions = listOf(session("first", "First Session")),
                openedSession =
                    OpenSessionUiState(
                        session = session("first", "First Session"),
                        messages = listOf(message("message-1", "user", "Conversation message")),
                    ),
            ),
        )

    private fun entryState(sessionList: SessionListUiState): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "Connected",
            actionLabel = "Connected",
            isConnected = true,
            sessionList = sessionList,
        )

    private fun session(
        id: String,
        title: String,
    ): SessionItemUiState = SessionItemUiState(id = SessionId(id), title = title, preview = null, pinned = false)

    private fun message(
        id: String,
        role: String,
        content: String,
    ): SessionMessageUiState = SessionMessageUiState(id = id, role = role, content = content)
}
