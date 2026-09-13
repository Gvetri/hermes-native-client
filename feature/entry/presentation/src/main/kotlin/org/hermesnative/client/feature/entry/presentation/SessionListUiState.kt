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

enum class SessionCreationErrorCategory(
    val safeMessage: String,
) {
    GATEWAY_REQUEST_FAILED("Session was not created. Your title is preserved. Try again."),
}

enum class SessionMutationAction {
    RENAME,
    PIN,
    UNPIN,
    DELETE,
}

enum class SessionMutationErrorCategory(
    val safeMessage: String,
) {
    GATEWAY_REQUEST_FAILED("Session action failed. No local changes were applied. Try again."),
}

enum class SessionRenameErrorCategory(
    val safeMessage: String,
) {
    EMPTY_TITLE("Enter a Session title."),
    CONTROL_CHARACTER("Session title cannot contain control characters."),
    GATEWAY_REQUEST_FAILED("Session was not renamed. The current title is preserved. Try again."),
}

data class SessionItemUiState(
    val id: SessionId,
    val title: String,
    val preview: String?,
    val pinned: Boolean,
    val updatedAt: String? = null,
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

data class SessionCreationUiState(
    val titleDraft: String = "",
    val isSubmitting: Boolean = false,
    val errorCategory: SessionCreationErrorCategory? = null,
)

data class SessionRenameUiState(
    val titleDraft: String,
    val isSubmitting: Boolean = false,
    val errorCategory: SessionRenameErrorCategory? = null,
)

data class SessionDeleteUiState(
    val isSubmitting: Boolean = false,
)

data class SessionMutationUiState(
    val rename: SessionRenameUiState? = null,
    val delete: SessionDeleteUiState? = null,
    val pendingAction: SessionMutationAction? = null,
    val errorCategory: SessionMutationErrorCategory? = null,
    val retryAction: SessionMutationAction? = null,
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
    val createSession: SessionCreationUiState? = null,
    val searchQuery: String = "",
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val isSearching: Boolean = false,
    val sessionMutations: Map<SessionId, SessionMutationUiState> = emptyMap(),
) {
    val hasPendingMutation: Boolean
        get() = sessionMutations.values.any { it.pendingAction != null }
}

internal fun SessionListUiState.allowsSessionMutation(): Boolean =
    !isLoading &&
        !isRefreshing &&
        !isLoadingMore &&
        !isSearching &&
        !isUnavailable &&
        !hasPendingMutation &&
        openingSessionId == null &&
        createSession == null

internal fun Session.toSessionItemUiState(): SessionItemUiState =
    SessionItemUiState(
        id = id,
        title = title?.takeIf(String::isNotBlank) ?: "Untitled Session",
        preview = preview?.takeIf(String::isNotBlank),
        pinned = pinned,
        updatedAt = updatedAt,
    )

internal fun GatewayHistoryMessage.toSessionMessageUiState(): SessionMessageUiState =
    SessionMessageUiState(
        id = id,
        role = role,
        content = content,
    )
