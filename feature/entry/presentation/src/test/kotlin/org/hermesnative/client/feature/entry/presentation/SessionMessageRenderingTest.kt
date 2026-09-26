package org.hermesnative.client.feature.entry.presentation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.hermesnative.client.feature.entry.domain.RunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The renderer inside the authoritative Session history and the streamed response, plus
 * the message-level Copy and Share actions that only an explicit user selection runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight")
class SessionMessageRenderingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun authoritative_history_renders_supported_markdown_as_native_text() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                                    role = "assistant",
                                                    content = "Safe **Markdown** text",
                                                ),
                                            ),
                                    ),
                            ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Safe Markdown text").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("**", substring = true).assertCountEquals(0)
    }

    @Test
    fun the_streamed_response_and_its_code_block_render_safely_while_it_grows() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                                    content = "Run the build",
                                                ),
                                            ),
                                        activeResponse =
                                            SessionMessageUiState(
                                                id = "message-2",
                                                role = "assistant",
                                                content = "Building now\n\n```kotlin\nval total = 1\n```",
                                                runId = RunId("run-1"),
                                                isStreaming = true,
                                            ),
                                    ),
                            ),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Building now").assertIsDisplayed()
        composeTestRule.onNodeWithText("val total = 1").assertExists()
        composeTestRule.onNodeWithText("Copy code").assertExists()
        composeTestRule.onNodeWithText("Streaming response…").assertExists()
    }

    @Test
    fun the_copy_action_carries_only_the_selected_message_content_and_needs_a_user_action() {
        val clipboard = clipboardManager()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("before", "before"))

        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
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
                                                    role = "assistant",
                                                    content = "Safe **Markdown** text",
                                                    runId = RunId("run-secret"),
                                                    runStatus = "failed",
                                                    failureTechnicalDetail = "diagnostic detail",
                                                ),
                                            ),
                                    ),
                            ),
                        ),
                    onEvent = {},
                )
            }
        }

        assertEquals("before", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeTestRule.onNodeWithText("Copy message").performClick()
        assertEquals("Safe **Markdown** text", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun the_share_action_carries_only_the_selected_message_content() {
        val recorded = RecordingActions()
        composeTestRule.setContent {
            HermesTheme {
                SessionMessageContent(
                    message =
                        SessionMessageUiState(
                            id = "message-1",
                            role = "assistant",
                            content = "Safe **Markdown** text",
                            runId = RunId("run-secret"),
                            runStatus = "failed",
                            failureTechnicalDetail = "diagnostic detail",
                            timestamp = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                        ),
                    retryEnabled = false,
                    onRetry = {},
                    actions = recorded.asMessageActions(),
                )
            }
        }

        assertTrue("Nothing may be shared before the user acts", recorded.shared.isEmpty())
        composeTestRule.onNodeWithText("Share message").performClick()
        assertEquals(listOf("Safe **Markdown** text"), recorded.shared)
    }

    private fun clipboardManager(): ClipboardManager =
        RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
