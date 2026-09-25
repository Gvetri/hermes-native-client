package org.hermesnative.client.feature.entry.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionShellNavigationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun system_back_on_a_phone_conversation_requests_the_session_list() {
        val events = mutableListOf<EntryUiEvent>()
        setContent(conversationEntryState(), events::add)

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(EntryUiEvent.ReturnToSessionListClicked), events)
    }

    @Test
    fun system_back_on_the_session_list_is_not_intercepted() {
        val events = mutableListOf<EntryUiEvent>()
        setContent(
            entryState(SessionListUiState(sessions = listOf(session("first", "First Session")))),
            events::add,
        )

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertTrue(events.isEmpty())
        assertTrue(composeTestRule.activity.isFinishing)
    }

    @Test
    fun system_back_on_the_creation_form_cancels_creation() {
        val events = mutableListOf<EntryUiEvent>()
        setContent(
            entryState(SessionListUiState(createSession = SessionCreationUiState())),
            events::add,
        )

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(EntryUiEvent.CancelCreateSessionClicked), events)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun system_back_cancels_creation_in_the_two_pane_detail_pane() {
        val events = mutableListOf<EntryUiEvent>()
        setContent(
            entryState(SessionListUiState(createSession = SessionCreationUiState())),
            events::add,
        )

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(EntryUiEvent.CancelCreateSessionClicked), events)
    }

    @Test
    fun delivered_system_bar_insets_move_the_shell_content() {
        val topInset = 120
        val bottomInset = 48
        setContent(conversationEntryState(), {})

        val backTopBefore = composeTestRule.onNodeWithText("Back to Sessions").fetchSemanticsNode().boundsInWindow.top
        val sendBottomBefore = composeTestRule.onNodeWithText("Send").fetchSemanticsNode().boundsInWindow.bottom

        composeTestRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topInset, 0, bottomInset))
                    .build()
            composeTestRule.activity.window.decorView.dispatchApplyWindowInsets(insets.toWindowInsets())
        }
        composeTestRule.waitForIdle()

        val backTopAfter = composeTestRule.onNodeWithText("Back to Sessions").fetchSemanticsNode().boundsInWindow.top
        val sendBottomAfter = composeTestRule.onNodeWithText("Send").fetchSemanticsNode().boundsInWindow.bottom

        assertEquals(topInset.toFloat(), backTopAfter - backTopBefore, 0.5f)
        assertEquals(bottomInset.toFloat(), sendBottomBefore - sendBottomAfter, 0.5f)
    }

    @Test
    fun delivered_ime_insets_do_not_collapse_the_session_list() {
        setContent(
            entryState(SessionListUiState(sessions = listOf(session("first", "First Session")))),
            {},
        )
        val rowHeightBefore =
            composeTestRule.onNodeWithText("First Session").fetchSemanticsNode().boundsInWindow.height

        composeTestRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 900))
                    .build()
            composeTestRule.activity.window.decorView.dispatchApplyWindowInsets(insets.toWindowInsets())
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("First Session").assertIsDisplayed()
        val rowHeightAfter =
            composeTestRule.onNodeWithText("First Session").fetchSemanticsNode().boundsInWindow.height
        assertEquals(rowHeightBefore, rowHeightAfter, 0.5f)
    }

    private fun setContent(
        state: EntryUiState,
        onEvent: (EntryUiEvent) -> Unit,
    ) {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state, onEvent = onEvent)
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
                        messages =
                            listOf(
                                SessionMessageUiState(
                                    id = "message-1",
                                    role = "user",
                                    content = "Conversation message",
                                ),
                            ),
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
}
