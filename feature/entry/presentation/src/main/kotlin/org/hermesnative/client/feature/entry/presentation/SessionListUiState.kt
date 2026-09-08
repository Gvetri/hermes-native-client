package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionId

enum class SessionListErrorCategory(
    val safeMessage: String,
) {
    GATEWAY_UNAVAILABLE("The Gateway is unavailable. The current Session list is preserved. Try again or reconnect."),
    SESSION_UNAVAILABLE("This Session could not be opened. It may have been removed. Return to the list or refresh."),
}

data class SessionItemUiState(
    val id: SessionId,
    val title: String,
    val preview: String?,
    val pinned: Boolean,
)

data class SessionMessageUiState(
    val id: String?,
    val role: String?,
    val content: String?,
)

data class OpenSessionUiState(
    val session: SessionItemUiState,
    val messages: List<SessionMessageUiState>,
)

data class SessionListUiState(
    val sessions: List<SessionItemUiState> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isStale: Boolean = false,
    val isUnavailable: Boolean = false,
    val errorCategory: SessionListErrorCategory? = null,
    val showFirstUseGuidance: Boolean = false,
    val openingSessionId: SessionId? = null,
    val openedSession: OpenSessionUiState? = null,
)

internal fun Session.toSessionItemUiState(): SessionItemUiState =
    SessionItemUiState(
        id = id,
        title = title?.takeIf(String::isNotBlank) ?: "Untitled Session",
        preview = preview?.takeIf(String::isNotBlank),
        pinned = pinned,
    )

internal fun GatewayHistoryMessage.toSessionMessageUiState(): SessionMessageUiState =
    SessionMessageUiState(
        id = id,
        role = role,
        content = content,
    )
