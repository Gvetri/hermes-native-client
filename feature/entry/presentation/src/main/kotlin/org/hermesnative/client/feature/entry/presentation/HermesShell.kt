package org.hermesnative.client.feature.entry.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

@Composable
private fun SinglePaneSessionContent(
    state: SessionListUiState,
    runStatusNotifications: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier,
) {
    when {
        state.openedSession != null -> ShellBackHandler { onEvent(EntryUiEvent.ReturnToSessionListClicked) }
        state.createSession != null -> ShellBackHandler { onEvent(EntryUiEvent.CancelCreateSessionClicked) }
    }
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
    if (state.createSession != null) {
        ShellBackHandler { onEvent(EntryUiEvent.CancelCreateSessionClicked) }
    }
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
        val openedSession = state.openedSession
        val createSession = state.createSession
        when {
            openedSession != null ->
                OpenedSessionContent(
                    state = state,
                    openedSession = openedSession,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxSize(),
                )
            createSession != null ->
                CreateSessionContent(
                    state = createSession,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxSize(),
                )
            else -> SessionPlaceholderPane(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SessionPlaceholderPane(modifier: Modifier = Modifier) {
    val spacing = LocalHermesDesignTokens.current.spacing
    Column(
        modifier = modifier.padding(horizontal = spacing.l),
        verticalArrangement = Arrangement.Center,
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
