package org.hermesnative.client.feature.entry.presentation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell visual contract: the same Session/conversation state rendered as an
 * intentional light and dark theme, on a phone single-pane window and on a
 * two-pane window. These captures pin the adaptive contract; they do not
 * replace the behavior and semantics tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedShellVisualRegressionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule()

    @Test
    fun phone_single_pane_light() {
        capture(darkTheme = false)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun phone_single_pane_dark() {
        capture(darkTheme = true)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-notnight")
    fun two_pane_light() {
        capture(darkTheme = false)
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp-night")
    fun two_pane_dark() {
        capture(darkTheme = true)
    }

    private fun capture(darkTheme: Boolean) {
        composeTestRule.setContent {
            HermesTheme(darkTheme = darkTheme) {
                EntryScreen(state = shellState(), onEvent = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage()
    }

    private fun releaseChecklistSession(): SessionItemUiState =
        session(
            id = "release-checklist",
            title = "Release checklist",
            preview = "Review the release checklist.",
            pinned = true,
        )

    private fun shellState(): EntryUiState =
        EntryUiState(
            title = "Gateway connected",
            supportingText = "",
            actionLabel = "",
            isConnected = true,
            sessionList =
                SessionListUiState(
                    sessions =
                        listOf(
                            releaseChecklistSession(),
                            session("follow-up", "Follow-up Session", preview = "Plan the next step."),
                        ),
                    openedSession =
                        OpenSessionUiState(
                            session = releaseChecklistSession(),
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
        )
}
