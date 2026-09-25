package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.SessionId

/**
 * Fixtures shared by the Session shell tests: an entry state that reports a connected
 * Gateway, a minimal Session row, and a conversation with one message.
 */
internal fun entryState(sessionList: SessionListUiState): EntryUiState =
    EntryUiState(
        title = "Gateway connected",
        supportingText = "Connected",
        actionLabel = "Connected",
        isConnected = true,
        sessionList = sessionList,
    )

internal fun session(
    id: String,
    title: String,
    preview: String? = null,
    pinned: Boolean = false,
): SessionItemUiState = SessionItemUiState(id = SessionId(id), title = title, preview = preview, pinned = pinned)

internal fun conversationEntryState(): EntryUiState =
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

internal fun message(
    id: String,
    role: String,
    content: String,
): SessionMessageUiState = SessionMessageUiState(id = id, role = role, content = content)
