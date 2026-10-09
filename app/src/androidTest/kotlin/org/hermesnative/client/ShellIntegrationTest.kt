package org.hermesnative.client

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.presentation.EntryScreen
import org.hermesnative.client.feature.entry.presentation.EntryUiEvent
import org.hermesnative.client.feature.entry.presentation.EntryUiState
import org.hermesnative.client.feature.entry.presentation.HermesTheme
import org.hermesnative.client.feature.entry.presentation.OpenSessionUiState
import org.hermesnative.client.feature.entry.presentation.SessionItemUiState
import org.hermesnative.client.feature.entry.presentation.SessionListUiState
import org.hermesnative.client.feature.entry.presentation.SessionMessageUiState
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration coverage for the adaptive shell: the real Android back
 * dispatcher returns from the conversation to the Session list, and the
 * conversation stays usable when the font scale increases.
 */
@RunWith(AndroidJUnit4::class)
class ShellIntegrationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun system_back_on_the_conversation_returns_to_the_session_list() {
        val state = mutableStateOf(conversationEntryState())
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state = state.value,
                    onEvent = { event ->
                        if (event == EntryUiEvent.ReturnToSessionListClicked) {
                            state.value = sessionListEntryState()
                        }
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("Back to Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sessions").assertDoesNotExist()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Back to Sessions").assertDoesNotExist()
        assertFalse(composeTestRule.activity.isFinishing)
    }

    @Test
    fun conversation_controls_stay_usable_at_increased_font_scale() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                HermesTheme {
                    EntryScreen(state = conversationEntryState(), onEvent = {})
                }
            }
        }

        composeTestRule
            .onNodeWithText("Back to Sessions")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Message").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Send")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
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

    private fun sessionListEntryState(): EntryUiState = entryState(SessionListUiState(sessions = listOf(session("first", "First Session"))))

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
