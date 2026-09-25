package org.hermesnative.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.presentation.EntryScreen
import org.hermesnative.client.feature.entry.presentation.EntryUiState
import org.hermesnative.client.feature.entry.presentation.HermesTheme
import org.hermesnative.client.feature.entry.presentation.OpenSessionUiState
import org.hermesnative.client.feature.entry.presentation.SessionItemUiState
import org.hermesnative.client.feature.entry.presentation.SessionListUiState
import org.hermesnative.client.feature.entry.presentation.SessionMessageUiState

/**
 * Debug-only host for the shell's on-device configuration tests. It renders a
 * fixed connected conversation and declares the orientation changes, so a real
 * rotation keeps one activity instance with the composition alive - the same
 * contract MainActivity declares for the app, which the app's own configuration
 * tests cannot exercise with a connected shell because that needs a Gateway.
 *
 * It lives in the debug source set because an activity declared by the androidTest
 * manifest would belong to the test process, which ActivityScenario cannot launch.
 */
class ShellHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesTheme {
                EntryScreen(state = conversationState(), onEvent = {})
            }
        }
    }
}

private fun conversationState(): EntryUiState =
    EntryUiState(
        title = "Gateway connected",
        supportingText = "Connected",
        actionLabel = "Connected",
        isConnected = true,
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

private fun session(
    id: String,
    title: String,
): SessionItemUiState = SessionItemUiState(id = SessionId(id), title = title, preview = null, pinned = false)

private fun message(
    id: String,
    role: String,
    content: String,
): SessionMessageUiState = SessionMessageUiState(id = id, role = role, content = content)
