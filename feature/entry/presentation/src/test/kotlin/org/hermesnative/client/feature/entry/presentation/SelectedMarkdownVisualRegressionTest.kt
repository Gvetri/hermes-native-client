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
 * The markdown rendering contract for untrusted Gateway content: a fenced code block in
 * its horizontally scrollable region, and long multi-block content, each captured in an
 * intentional light and dark theme on a fixed phone viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedMarkdownVisualRegressionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule()

    @Test
    fun markdown_code_block_light() {
        capture(darkTheme = false, content = CODE_BLOCK_MESSAGE)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun markdown_code_block_dark() {
        capture(darkTheme = true, content = CODE_BLOCK_MESSAGE)
    }

    @Test
    fun markdown_long_content_light() {
        capture(darkTheme = false, content = LONG_MESSAGE)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night")
    fun markdown_long_content_dark() {
        capture(darkTheme = true, content = LONG_MESSAGE)
    }

    private fun capture(
        darkTheme: Boolean,
        content: String,
    ) {
        composeTestRule.setContent {
            HermesTheme(darkTheme = darkTheme) {
                EntryScreen(state = markdownState(content), onEvent = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage()
    }

    private fun markdownState(content: String): EntryUiState {
        val session = session("markdown-session", "Markdown Session", preview = "Synthetic preview")
        return entryState(
            SessionListUiState(
                sessions = listOf(session),
                openedSession =
                    OpenSessionUiState(
                        session = session,
                        messages =
                            listOf(
                                SessionMessageUiState(
                                    id = "message-1",
                                    role = "user",
                                    content = "Show the release notes",
                                ),
                                SessionMessageUiState(
                                    id = "message-2",
                                    role = "assistant",
                                    content = content,
                                ),
                            ),
                    ),
            ),
        )
    }

    private companion object {
        val CODE_BLOCK_MESSAGE =
            """
            Run **this** build command and read [the notes](https://example.com/notes):

            ```shell
            ./gradlew :feature:entry:presentation:verifyRoborazziDebug --console=plain --no-daemon --stacktrace
            ```

            A rejected scheme such as [danger](javascript:alert(1)) stays inert text.
            """.trimIndent()

        val LONG_MESSAGE =
            """
            **Summary**

            - first finding with `inline code`
            - second finding with [the release notes](https://example.com/notes)
            - third finding that also proves the list keeps its items

            The response continues with more paragraphs than a phone pane can show at once, so the
            conversation scrolls natively instead of truncating the message.
            """.trimIndent() +
                "\n\n" +
                (1..12).joinToString(separator = "\n\n") { index -> "Paragraph $index of the long response." } +
                "\n\n```kotlin\nval total = 12\n```"
    }
}
