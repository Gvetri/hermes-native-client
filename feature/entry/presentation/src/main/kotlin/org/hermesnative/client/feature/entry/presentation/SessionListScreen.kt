package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun SessionListContent(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
) {
    SessionModeContent(state = state, onEvent = onEvent, modifier = modifier) {
        SessionListPane(
            state = state,
            onEvent = onEvent,
            modifier = modifier,
            runStatusNotifications = runStatusNotifications,
        )
    }
}

/**
 * Renders the surface that matches the current [SessionShellMode]. Callers differ only
 * in the fallback they show while the Session list itself is on screen.
 */
@Composable
internal fun SessionModeContent(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    when (state.shellMode()) {
        SessionShellMode.OpenedSession ->
            OpenedSessionContent(
                state = state,
                openedSession = requireNotNull(state.openedSession),
                onEvent = onEvent,
                modifier = modifier.imePadding(),
            )
        SessionShellMode.CreateSession ->
            CreateSessionContent(
                state = requireNotNull(state.createSession),
                onEvent = onEvent,
                modifier = modifier,
            )
        SessionShellMode.SessionList -> fallback()
    }
}

@Composable
internal fun OpenedSessionContent(
    state: SessionListUiState,
    openedSession: OpenSessionUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SessionDetailContent(
        detail =
            SessionDetailState(
                content = openedSession,
                mutation = state.sessionMutations[openedSession.session.id],
                actionsEnabled = state.allowsSessionMutation(),
                listRequestActive = state.hasActiveRequest,
                listIsStale = state.isStale,
                listErrorCategory = state.errorCategory,
            ),
        onEvent = onEvent,
        modifier = modifier,
    )
}
