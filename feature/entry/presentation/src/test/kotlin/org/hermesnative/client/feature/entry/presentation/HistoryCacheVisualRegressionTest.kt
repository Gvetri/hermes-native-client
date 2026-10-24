package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
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
class HistoryCacheVisualRegressionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule()

    @Test
    fun cached_session_list() {
        setContent(cachedListState())
        capture()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun cached_session_list_dark() {
        setContent(cachedListState(), darkTheme = true)
        capture()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-notnight")
    fun cached_session_list_wide_light() {
        setContent(cachedListState())
        capture()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-night")
    fun cached_session_list_wide_dark() {
        setContent(cachedListState(), darkTheme = true)
        capture()
    }

    @Test
    fun cached_session_list_200_light() {
        setScaledContent(cachedListState())
        capture()
    }

    @Test
    fun cached_transcript() {
        setContent(cachedTranscriptState())
        capture()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun cached_transcript_dark() {
        setContent(cachedTranscriptState(), darkTheme = true)
        capture()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-notnight")
    fun cached_transcript_wide_light() {
        setContent(cachedTranscriptState())
        capture()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-night")
    fun cached_transcript_wide_dark() {
        setContent(cachedTranscriptState(), darkTheme = true)
        capture()
    }

    @Test
    fun cached_transcript_200_light() {
        setScaledContent(cachedTranscriptState())
        capture()
    }

    private fun cachedListState(): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "",
            actionLabel = "",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    sessions = cachedRows(),
                    isStale = true,
                    isCached = true,
                    isUnavailable = true,
                    errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                ),
        )

    private fun cachedTranscriptState(): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "",
            actionLabel = "",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    sessions = cachedRows(),
                    isStale = true,
                    isCached = true,
                    openedSession =
                        OpenSessionUiState(
                            session =
                                SessionItemUiState(
                                    id = SessionId("pinned-session"),
                                    title = "Release checklist",
                                    preview = "Review the release checklist.",
                                    pinned = true,
                                ),
                            messages =
                                listOf(
                                    SessionMessageUiState(
                                        id = "cached-message-1",
                                        role = "user",
                                        content = "Show the release checklist.",
                                    ),
                                    SessionMessageUiState(
                                        id = "cached-message-2",
                                        role = "assistant",
                                        content = "The release checklist is ready.",
                                    ),
                                ),
                            isStale = true,
                            isCached = true,
                        ),
                ),
        )

    private fun cachedRows(): List<SessionItemUiState> =
        listOf(
            SessionItemUiState(
                id = SessionId("pinned-session"),
                title = "Release checklist",
                preview = "Review the release checklist.",
                pinned = true,
            ),
            SessionItemUiState(
                id = SessionId("follow-up-session"),
                title = "Follow-up Session",
                preview = "Plan the next step.",
                pinned = false,
            ),
        )

    private fun setContent(
        state: EntryUiState,
        darkTheme: Boolean? = null,
    ) {
        composeTestRule.setContent {
            HermesTheme(darkTheme = darkTheme ?: isSystemInDarkTheme()) {
                EntryScreen(state = state, onEvent = {})
            }
        }
    }

    private fun setScaledContent(state: EntryUiState) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesTheme(darkTheme = false) {
                    EntryScreen(state = state, onEvent = {})
                }
            }
        }
    }

    private fun capture() {
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage()
    }
}
