package org.hermesnative.client.feature.entry.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

internal const val TWO_PANE_MIN_WIDTH_DP = 600

private const val SESSION_LIST_PANE_WEIGHT = 2f
private const val CONVERSATION_PANE_WEIGHT = 3f

/** Which surface the shell shows for the current list state. */
internal enum class SessionShellMode { SessionList, OpenedSession, CreateSession }

/**
 * Derives the active surface once. Creation and an open conversation are mutually
 * exclusive: starting creation closes the conversation.
 */
internal fun SessionListUiState.shellMode(): SessionShellMode =
    when {
        openedSession != null -> SessionShellMode.OpenedSession
        createSession != null -> SessionShellMode.CreateSession
        else -> SessionShellMode.SessionList
    }

/**
 * The session shell. Phones stay single-pane: the Session list is the entry
 * surface and the conversation replaces it, with the system back action
 * returning to the list. Wider displays use the same code to render an
 * adaptive two-pane Session/conversation layout, without a separate platform
 * target, and re-evaluate on any window width change.
 */
@Composable
internal fun SessionShell(
    state: SessionListUiState,
    runStatusNotifications: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP) {
        TwoPaneSessionContent(state, runStatusNotifications, onEvent, modifier)
    } else {
        SinglePaneSessionContent(state, runStatusNotifications, onEvent, modifier)
    }
}

/**
 * System Back follows the same path as the visible controls: an open rename or delete
 * confirmation is dismissed first, then an open conversation returns to the Session list
 * and an open creation is cancelled, in both layouts.
 */
@Composable
private fun SessionShellBackHandling(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val confirmation =
        state.sessionMutations.entries
            .firstOrNull { it.value.rename != null || it.value.delete != null }
    when {
        confirmation != null ->
            ShellBackHandler {
                val sessionId = confirmation.key
                onEvent(
                    if (confirmation.value.rename != null) {
                        EntryUiEvent.CancelRenameSessionClicked(sessionId)
                    } else {
                        EntryUiEvent.CancelDeleteSessionClicked(sessionId)
                    },
                )
            }
        state.shellMode() == SessionShellMode.OpenedSession ->
            ShellBackHandler { onEvent(EntryUiEvent.ReturnToSessionListClicked) }
        state.shellMode() == SessionShellMode.CreateSession ->
            ShellBackHandler { onEvent(EntryUiEvent.CancelCreateSessionClicked) }
        else -> Unit
    }
}

@Composable
private fun SinglePaneSessionContent(
    state: SessionListUiState,
    runStatusNotifications: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier,
) {
    SessionShellBackHandling(state, onEvent)
    SessionListContent(
        state = state,
        onEvent = onEvent,
        modifier = modifier,
        runStatusNotifications = runStatusNotifications,
    )
}

@Composable
private fun TwoPaneSessionContent(
    state: SessionListUiState,
    runStatusNotifications: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier,
) {
    SessionShellBackHandling(state, onEvent)
    Row(modifier = modifier.fillMaxSize()) {
        SessionListPane(
            state = state,
            onEvent = onEvent,
            modifier = Modifier.weight(SESSION_LIST_PANE_WEIGHT).fillMaxHeight(),
            runStatusNotifications = runStatusNotifications,
            selectedSessionId = state.openedSession?.session?.id,
        )
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        ConversationPaneHost(
            state = state,
            onEvent = onEvent,
            modifier = Modifier.weight(CONVERSATION_PANE_WEIGHT).fillMaxHeight(),
        )
    }
}

@Composable
private fun ConversationPaneHost(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = LocalHermesDesignTokens.current.elevation.raised,
    ) {
        SessionModeContent(
            state = state,
            onEvent = onEvent,
            modifier = Modifier.fillMaxSize(),
        ) {
            SessionPlaceholderPane(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SessionPlaceholderPane(modifier: Modifier = Modifier) {
    val spacing = LocalHermesDesignTokens.current.spacing
    // Centered while the guidance fits, scrollable as soon as the pane or the font scale
    // grows, so the guidance can never be clipped without a way to reveal it.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No Session selected",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(spacing.s))
            Text(
                text = "Select a Session from the list to view its conversation.",
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Registers the shell back action when a back dispatcher owner exists. Plain
 * composition tests have no owner, so previews and state tests stay hermetic.
 */
@Composable
private fun ShellBackHandler(onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = onBack)
    }
}
