package org.hermesnative.client.feature.entry.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the transcript follow behavior for a message taller than the pane: an opened Session
 * shows the newest content's end, a streamed response that grows in place stays followed to its
 * newest lines, a long arriving message is followed to its end, a reader who dragged away is not
 * pulled back, and the follow returns once the reader settles back at the newest content.
 *
 * Every transcript here is taller than the pane before the asserted change, so a pass cannot come
 * from layout slack: the newest line only ends up displayed when the transcript is actually
 * pinned to the newest content's end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionTranscriptFollowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun opened_session_shows_the_newest_content_of_a_transcript_taller_than_the_pane() {
        setTranscriptContent { streamingState(activeResponse = streamedResponse(transcriptBody(48))) }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()
        // The message's last line sits at the viewport's end, so the end is pinned, not merely
        // near the fold.
        composeTestRule.onNodeWithText("Streaming response…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paragraph 1").assertIsNotDisplayed()
    }

    @Test
    fun growing_streamed_response_stays_in_view_while_the_reader_stays_at_the_newest_content() {
        val state = mutableStateOf(streamingState(activeResponse = streamedResponse(transcriptBody(48))))
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()
        composeTestRule.onNodeWithText("Streaming response…").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(60)))
        }

        composeTestRule.onNodeWithText("Paragraph 60").assertIsDisplayed()
    }

    @Test
    fun newly_arrived_message_stays_in_view_while_the_reader_stays_at_the_newest_content() {
        val longReply = message("message-2", "assistant", transcriptBody(48))
        val state =
            mutableStateOf(
                streamingState(
                    messages =
                        listOf(
                            message("message-1", "user", "Show me the long answer"),
                            longReply,
                        ),
                ),
            )
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value =
                streamingState(
                    messages =
                        listOf(
                            message("message-1", "user", "Show me the long answer"),
                            longReply,
                            message("message-3", "assistant", "Newly arrived reply"),
                        ),
                )
        }

        composeTestRule.onNodeWithText("Newly arrived reply").assertIsDisplayed()
    }

    @Test
    fun a_reader_who_dragged_away_from_the_newest_content_is_not_pulled_back_by_growth() {
        val state = mutableStateOf(streamingState(activeResponse = streamedResponse(transcriptBody(48))))
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        composeTestRule.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeDown(startY = centerY - 200f, endY = centerY + 200f, durationMillis = 200)
        }
        composeTestRule.onNodeWithText("Paragraph 48").assertIsNotDisplayed()

        composeTestRule.runOnIdle {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(60)))
        }

        composeTestRule.onNodeWithText("Paragraph 60").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Paragraph 48").assertIsNotDisplayed()
    }

    @Test
    fun follow_returns_once_the_reader_settles_back_at_the_newest_content() {
        val state = mutableStateOf(streamingState(activeResponse = streamedResponse(transcriptBody(48))))
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        composeTestRule.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeDown(startY = centerY - 200f, endY = centerY + 200f, durationMillis = 200)
        }
        composeTestRule.onNodeWithText("Paragraph 48").assertIsNotDisplayed()

        // Settle back at the newest content: the swipes overshoot the end, so the transcript
        // clamps there regardless of how far the away drag carried.
        repeat(3) {
            composeTestRule.onNode(hasScrollToIndexAction()).performTouchInput {
                swipeUp(startY = centerY + 240f, endY = centerY - 240f, durationMillis = 200)
            }
        }
        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(60)))
        }

        composeTestRule.onNodeWithText("Paragraph 60").assertIsDisplayed()
    }

    @Test
    fun a_long_arriving_message_is_followed_to_its_end() {
        val state =
            mutableStateOf(
                streamingState(
                    messages =
                        listOf(
                            message("message-1", "user", "Show me the long answer"),
                            message("message-2", "assistant", transcriptBody(48)),
                        ),
                ),
            )
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        composeTestRule.runOnIdle {
            state.value =
                streamingState(
                    messages =
                        listOf(
                            message("message-1", "user", "Show me the long answer"),
                            message("message-2", "assistant", transcriptBody(48)),
                            message("message-3", "assistant", transcriptBody(30, label = "Second reply")),
                        ),
                )
        }

        composeTestRule.onNodeWithText("Second reply 30").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second reply 1").assertIsNotDisplayed()
    }

    @Test
    fun a_burst_of_chunks_still_ends_at_the_newest_content() {
        val state = mutableStateOf(streamingState(activeResponse = streamedResponse(transcriptBody(48))))
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        // Two chunks arrive before the next idle, so the follow has to coalesce or re-anchor
        // instead of fighting an in-flight scroll.
        composeTestRule.runOnUiThread {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(60)))
        }
        composeTestRule.runOnUiThread {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(72)))
        }

        composeTestRule.onNodeWithText("Paragraph 72").assertIsDisplayed()
    }

    @Test
    fun a_chunk_arriving_right_after_a_follow_scroll_still_ends_at_the_newest_content() {
        val state = mutableStateOf(streamingState(activeResponse = streamedResponse(transcriptBody(48))))
        setTranscriptContent { state.value }

        composeTestRule.onNodeWithText("Paragraph 48").assertIsDisplayed()

        // The second chunk lands while the follow of the first one has not settled, so the follow
        // must re-anchor instead of cancelling into a stalled position.
        composeTestRule.runOnUiThread {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(60)))
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.runOnUiThread {
            state.value = streamingState(activeResponse = streamedResponse(transcriptBody(72)))
        }

        composeTestRule.onNodeWithText("Paragraph 72").assertIsDisplayed()
    }

    private fun setTranscriptContent(state: () -> EntryUiState) {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = state(), onEvent = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun streamingState(
        activeResponse: SessionMessageUiState? = null,
        messages: List<SessionMessageUiState> = listOf(message("message-1", "user", "Show me the long answer")),
    ): EntryUiState =
        entryState(
            SessionListUiState(
                openedSession =
                    OpenSessionUiState(
                        session = session("session-1", "Streaming Session"),
                        messages = messages,
                        activeResponse = activeResponse,
                    ),
            ),
        )

    private fun streamedResponse(content: String): SessionMessageUiState =
        SessionMessageUiState(
            id = "streamed-response",
            role = "assistant",
            content = content,
            runId = RunId("run-1"),
            runState = RunPresentationState.RUNNING,
            isStreaming = true,
        )

    /**
     * One Markdown paragraph per sentence, so a message is a single LazyColumn item that can grow
     * in place exactly as later chunks arrive.
     */
    private fun transcriptBody(
        paragraphCount: Int,
        label: String = "Paragraph",
    ): String =
        (1..paragraphCount).joinToString("\n\n") { number ->
            "$label $number"
        }
}
