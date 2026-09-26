package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.SessionId

/**
 * Fixtures shared by the Session shell tests: an entry state that reports a connected
 * Gateway, a minimal Session row, and a conversation with one message.
 */
internal fun entryState(
    sessionList: SessionListUiState,
    runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
): EntryUiState =
    EntryUiState(
        title = "Gateway connected",
        supportingText = "Connected",
        actionLabel = "Connected",
        isConnected = true,
        sessionList = sessionList,
        runStatusNotifications = runStatusNotifications,
    )

internal fun session(
    id: String,
    title: String,
    preview: String? = null,
    pinned: Boolean = false,
): SessionItemUiState = SessionItemUiState(id = SessionId(id), title = title, preview = preview, pinned = pinned)

internal fun conversationEntryState(
    sessionTitle: String = "First Session",
    sessionPreview: String? = null,
    composerText: String = "",
): EntryUiState =
    entryState(
        SessionListUiState(
            sessions = listOf(session("first", sessionTitle, preview = sessionPreview)),
            openedSession =
                OpenSessionUiState(
                    session = session("first", sessionTitle, preview = sessionPreview),
                    messages = listOf(message("message-1", "user", "Conversation message")),
                    composerText = composerText,
                ),
        ),
    )

internal fun message(
    id: String,
    role: String,
    content: String,
): SessionMessageUiState = SessionMessageUiState(id = id, role = role, content = content)
