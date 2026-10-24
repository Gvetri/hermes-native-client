package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class HistoryCacheCachedMarkerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cached_list_marker_is_displayed_above_cached_entries() {
        setConnectedContent(
            SessionListUiState(
                sessions = cachedRows(),
                isStale = true,
                isCached = true,
                errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithText("Row Session 01").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Sessions are cached on this device. They are not live.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Session data may be stale.")
            .assertIsDisplayed()
    }

    @Test
    fun a_live_stale_list_shows_no_cached_marker() {
        setConnectedContent(
            SessionListUiState(
                sessions = cachedRows(),
                isStale = true,
                isCached = false,
                errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithText("Row Session 01").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Sessions are cached on this device. They are not live.")
            .assertDoesNotExist()
    }

    @Test
    fun a_cached_transcript_is_displayed_with_its_own_cached_marker() {
        setConnectedContent(cachedTranscriptList())

        composeTestRule.onNodeWithText("Cached transcript message").assertExists()
        composeTestRule
            .onNodeWithText("The displayed Session history is cached on this device. It is not live.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Session history may be stale.")
            .assertIsDisplayed()
    }

    @Test
    fun a_live_transcript_shows_no_cached_marker() {
        setConnectedContent(
            SessionListUiState(
                openedSession =
                    OpenSessionUiState(
                        session = SessionItemUiState(SessionId("row-01"), "Row Session 01", null, false),
                        messages =
                            listOf(SessionMessageUiState(id = "live-message", role = "user", content = "Live message")),
                        isStale = true,
                        isCached = false,
                    ),
            ),
        )

        composeTestRule.onNodeWithText("Live message").assertExists()
        composeTestRule
            .onNodeWithText("The displayed Session history is cached on this device. It is not live.")
            .assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-notnight")
    fun cached_list_marker_is_displayed_in_the_two_pane_list() {
        setConnectedContent(
            SessionListUiState(
                sessions = cachedRows(),
                isStale = true,
                isCached = true,
                errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithText("Row Session 01").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Sessions are cached on this device. They are not live.")
            .assertIsDisplayed()
    }

    @Test
    fun an_empty_cache_offline_read_shows_no_entries_and_no_cache_marker() {
        setConnectedContent(
            SessionListUiState(
                sessions = emptyList(),
                isStale = true,
                isUnavailable = true,
                errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithText("Sessions are unavailable").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Sessions are cached on this device. They are not live.")
            .assertDoesNotExist()
    }

    @Test
    fun a_cached_list_without_a_cached_transcript_keeps_its_marker_and_reports_the_session_unavailable() {
        setConnectedContent(
            SessionListUiState(
                sessions = cachedRows(),
                isStale = true,
                isCached = true,
                isUnavailable = true,
                errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithText("Row Session 01").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("The displayed Sessions are cached on this device. They are not live.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "This Session could not be opened. It may have been removed. " +
                    "Return to the list or refresh.",
            )
            .assertIsDisplayed()
    }

    private fun setConnectedContent(sessionList: SessionListUiState) {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(
                    state =
                        EntryUiState(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isConnected = true,
                            sessionList = sessionList,
                        ),
                    onEvent = {},
                )
            }
        }
    }

    private fun cachedTranscriptList(): SessionListUiState =
        SessionListUiState(
            sessions = cachedRows(),
            isStale = true,
            isCached = true,
            openedSession =
                OpenSessionUiState(
                    session = SessionItemUiState(SessionId("row-01"), "Row Session 01", null, false),
                    messages =
                        listOf(
                            SessionMessageUiState(
                                id = "cached-message",
                                role = "user",
                                content = "Cached transcript message",
                            ),
                        ),
                    isStale = true,
                    isCached = true,
                ),
        )

    private fun cachedRows(count: Int = 20): List<SessionItemUiState> =
        (1..count).map { index ->
            SessionItemUiState(
                id = SessionId("row-%02d".format(index)),
                title = "Row Session %02d".format(index),
                preview = "Preview %02d".format(index),
                pinned = false,
            )
        }
}
