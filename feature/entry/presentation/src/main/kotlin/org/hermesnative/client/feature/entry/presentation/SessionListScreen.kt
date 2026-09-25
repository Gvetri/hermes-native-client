package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunSubmissionState
import org.hermesnative.client.feature.entry.domain.SessionId
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
                // Only the conversation consumes IME insets, so the soft keyboard lifts the
                // composer and Send instead of covering them, while the Session list keeps
                // its height and never collapses.
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
        state = openedSession,
        mutation = state.sessionMutations[openedSession.session.id],
        actionsEnabled = state.allowsSessionMutation(),
        listRequestActive = state.hasActiveRequest,
        listIsStale = state.isStale,
        listErrorCategory = state.errorCategory,
        onEvent = onEvent,
        modifier = modifier,
    )
}

@Composable
internal fun SessionListPane(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
    selectedSessionId: SessionId? = null,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The list owns the height above the pinned controls. When the window is too short to
        // keep a working list, the pinned tail scrolls on its own instead of starving the list,
        // so rows, warnings and every control stay reachable at any pane height.
        val pinnedTailMaxHeight = (maxHeight - 120.dp).coerceAtLeast(0.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("session-list"),
                verticalArrangement = Arrangement.Top,
            ) {
                item { SessionListPaneHeader(state = state, onEvent = onEvent) }
                if (state.sessions.isEmpty()) {
                    item { SessionListStateContent(state = state) }
                    item { SessionListPaginationFooterIfAvailable(state = state, onEvent = onEvent) }
                } else {
                    if (state.isSearching) {
                        item {
                            Text(
                                text = "Searching Sessions…",
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Spacer(modifier = Modifier.height(spacing.s))
                        }
                    }
                    items(
                        items = state.sessions,
                        key = { session -> session.id.value },
                    ) { session ->
                        SessionRow(
                            session = session,
                            mutation = state.sessionMutations[session.id],
                            selected = session.id == selectedSessionId,
                            enabled = state.allowsSessionMutation(),
                            onClick = { onEvent(EntryUiEvent.SessionClicked(session.id)) },
                            onEvent = onEvent,
                        )
                        Spacer(modifier = Modifier.height(spacing.s))
                    }
                    item {
                        SessionPaginationFooter(state = state, onEvent = onEvent)
                    }
                }
                item { SessionListStatusTexts(state = state) }
            }
            Column(
                modifier =
                    Modifier
                        .heightIn(max = pinnedTailMaxHeight)
                        .verticalScroll(rememberScrollState()),
            ) {
                SessionListPaneControls(
                    state = state,
                    onEvent = onEvent,
                    runStatusNotifications = runStatusNotifications,
                )
            }
        }
    }
}

@Composable
private fun SessionListPaneHeader(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.RemoveGatewayConnectionClicked) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Remove Gateway Connection")
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.showFirstUseGuidance) {
            Text(
                text = "Select a Session to open its Gateway history. Refresh to load the latest server state.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { onEvent(EntryUiEvent.SessionSearchQueryChanged(it)) },
            enabled = state.sessionMutations.isEmpty(),
            label = { Text("Search Sessions") },
            placeholder = { Text("Search titles and previews") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.searchQuery.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onEvent(EntryUiEvent.ClearSessionSearchClicked) },
                enabled = state.sessionMutations.isEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(text = "Clear search")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onEvent(EntryUiEvent.CreateSessionClicked) },
            enabled =
                !state.isLoading &&
                    !state.isSearching &&
                    !state.isRefreshing &&
                    !state.isLoadingMore &&
                    !state.isUnavailable &&
                    state.openingSessionId == null &&
                    state.sessionMutations.isEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Create Session")
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** State content for a pane without rows; panes with rows render the list instead. */
@Composable
private fun SessionListStateContent(state: SessionListUiState) {
    if ((state.isLoading || state.isSearching) && state.sessions.isEmpty()) {
        LoadingSessionsContent(if (state.isSearching) "Searching Sessions…" else "Loading Sessions…")
    } else if (state.sessions.isEmpty() && (state.isUnavailable || state.isStale)) {
        if (state.searchQuery.isNotBlank()) {
            SearchUnavailableContent()
        } else {
            SessionsUnavailableContent()
        }
    } else if (state.searchQuery.isNotBlank() && state.sessions.isEmpty()) {
        NoSearchResultsContent()
    } else if (state.sessions.isEmpty()) {
        EmptySessionsContent()
    }
}

@Composable
private fun SessionListPaginationFooterIfAvailable(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    if (
        state.sessions.isEmpty() &&
        !state.isLoading &&
        !state.isSearching &&
        state.nextCursor != null
    ) {
        SessionPaginationFooter(state = state, onEvent = onEvent)
    }
}

@Composable
private fun SessionListStatusTexts(state: SessionListUiState) {
    if (state.openingSessionId != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Opening Session…",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.isRefreshing) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Refreshing Sessions…",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.isUnavailable || state.isStale) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text =
                if (state.isUnavailable) {
                    "The displayed Session data may be stale. Gateway actions are unavailable until the connection recovers."
                } else {
                    "The displayed Session data may be stale."
                },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    state.errorCategory?.let { category ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = category.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

/** Controls pinned below the list area: refresh and the notification settings stay reachable. */
@Composable
private fun SessionListPaneControls(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    runStatusNotifications: RunStatusNotificationsUiState,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = { onEvent(EntryUiEvent.RefreshSessionListClicked) },
        enabled =
            !state.isLoading &&
                !state.isRefreshing &&
                state.openingSessionId == null &&
                !state.hasPendingMutation &&
                state.openedSession?.isRefreshing != true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = if (state.isUnavailable) "Try again" else "Refresh")
    }

    RunStatusNotificationSettingsContent(
        state = runStatusNotifications,
        onEvent = onEvent,
    )
}

@Composable
private fun RunStatusNotificationSettingsContent(
    state: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Run status notifications",
                style = MaterialTheme.typography.bodyLarge,
            )
            state.explanation?.let { explanation ->
                Text(
                    text = explanation.safeMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = state.enabled,
            onCheckedChange = { onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked) },
            modifier =
                Modifier.semantics {
                    contentDescription = "Run status notifications"
                },
        )
    }
}

@Composable
private fun LoadingSessionsContent(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message)
    }
}

@Composable
private fun NoSearchResultsContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No Sessions match this search",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SearchUnavailableContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Search results are unavailable",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SessionsUnavailableContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sessions are unavailable",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SessionPaginationFooter(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    when {
        state.isLoadingMore ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Loading more Sessions…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        state.nextCursor != null ->
            Button(
                onClick = { onEvent(EntryUiEvent.LoadMoreSessionsClicked) },
                enabled =
                    !state.isUnavailable &&
                        !state.isRefreshing &&
                        state.sessionMutations.isEmpty() &&
                        state.openedSession?.isRefreshing != true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(text = "Load more Sessions")
            }
    }
}

@Composable
private fun EmptySessionsContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No Sessions on this Gateway",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "The Gateway returned no Sessions. Create one to get started.")
    }
}

@Composable
internal fun CreateSessionContent(
    state: SessionCreationUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.CancelCreateSessionClicked) },
            enabled = !state.isSubmitting,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = "Cancel")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Create Session",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Optionally add a title. The Session is created only after confirmation.")
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.titleDraft,
            onValueChange = { onEvent(EntryUiEvent.CreateSessionTitleChanged(it)) },
            enabled = !state.isSubmitting,
            label = { Text("Session title (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.ConfirmCreateSessionClicked) },
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = if (state.errorCategory == null) "Confirm Create Session" else "Try again")
        }
        if (state.isSubmitting) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = "Creating Session…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.errorCategory?.let { category ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = category.safeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionItemUiState,
    mutation: SessionMutationUiState?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onClick,
            enabled = enabled && mutation?.pendingAction == null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics { this.selected = selected },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected) {
                            Text(
                                text = "Open",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (session.pinned) {
                            Text(
                                text = "Pinned",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                session.preview?.let { preview ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        SessionActionControls(
            session = session,
            mutation = mutation,
            enabled = enabled,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun SessionActionControls(
    session: SessionItemUiState,
    mutation: SessionMutationUiState?,
    enabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    when {
        mutation?.rename != null ->
            RenameSessionContent(
                session = session,
                state = mutation.rename,
                enabled = enabled,
                onEvent = onEvent,
            )
        mutation?.delete != null ->
            DeleteSessionContent(
                session = session,
                state = mutation.delete,
                errorCategory = mutation.errorCategory,
                enabled = enabled,
                onEvent = onEvent,
            )
        else -> {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        onEvent(
                            when (mutation?.retryAction) {
                                SessionMutationAction.PIN -> EntryUiEvent.PinSessionClicked(session.id)
                                SessionMutationAction.UNPIN -> EntryUiEvent.UnpinSessionClicked(session.id)
                                else ->
                                    if (session.pinned) {
                                        EntryUiEvent.UnpinSessionClicked(session.id)
                                    } else {
                                        EntryUiEvent.PinSessionClicked(session.id)
                                    }
                            },
                        )
                    },
                    enabled = enabled && mutation?.pendingAction == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        text =
                            if (mutation?.retryAction == SessionMutationAction.PIN ||
                                mutation?.retryAction == SessionMutationAction.UNPIN
                            ) {
                                "Try again"
                            } else if (session.pinned) {
                                "Unpin Session"
                            } else {
                                "Pin Session"
                            },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onEvent(EntryUiEvent.RenameSessionClicked(session.id)) },
                    enabled = enabled && mutation?.pendingAction == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(text = "Rename Session")
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { onEvent(EntryUiEvent.DeleteSessionClicked(session.id)) },
                    enabled = enabled && mutation?.pendingAction == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(text = "Delete Session")
                }
                mutation?.pendingAction?.let { action ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = action.pendingMessage(),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                mutation?.errorCategory?.let { category ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = category.safeMessage,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameSessionContent(
    session: SessionItemUiState,
    state: SessionRenameUiState,
    enabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            text = "Rename Session",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Current title: ${session.title}")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.titleDraft,
            onValueChange = { onEvent(EntryUiEvent.RenameSessionTitleChanged(session.id, it)) },
            enabled = enabled && !state.isSubmitting,
            label = { Text("New Session title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.ConfirmRenameSessionClicked(session.id)) },
            enabled = enabled && !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                text =
                    if (state.errorCategory == SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED) {
                        "Try again"
                    } else {
                        "Confirm Rename Session"
                    },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.CancelRenameSessionClicked(session.id)) },
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Cancel Rename")
        }
        if (state.isSubmitting) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = "Renaming Session…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.errorCategory?.let { category ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.safeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
private fun DeleteSessionContent(
    session: SessionItemUiState,
    state: SessionDeleteUiState,
    errorCategory: SessionMutationErrorCategory?,
    enabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(
            text = "Delete Session",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Delete \"${session.title}\"?")
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Remote deletion cannot be undone by this client.")
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(session.id)) },
            enabled = enabled && !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = if (errorCategory == null) "Confirm Delete Session" else "Try again")
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.CancelDeleteSessionClicked(session.id)) },
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Cancel Delete")
        }
        if (state.isSubmitting) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = "Deleting Session…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        errorCategory?.let { category ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.safeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

@Composable
internal fun SessionDetailContent(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    actionsEnabled: Boolean,
    listRequestActive: Boolean,
    listIsStale: Boolean,
    listErrorCategory: SessionListErrorCategory?,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayedMessages = state.messages + listOfNotNull(state.activeResponse)
    // Without a transcript there is no weighted list to absorb the free space, so the pane
    // scrolls as one surface and the composer and Send stay reachable in short windows.
    val paneScrollState = rememberScrollState()
    Column(
        modifier =
            if (displayedMessages.isEmpty()) {
                modifier.fillMaxSize().verticalScroll(paneScrollState)
            } else {
                modifier.fillMaxSize()
            },
        verticalArrangement = Arrangement.Top,
    ) {
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.ReturnToSessionListClicked) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(text = "Back to Sessions")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = state.session.title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        state.session.preview?.let { preview ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = preview, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (displayedMessages.isEmpty()) {
            SessionDetailSummary(
                state = state,
                mutation = mutation,
                actionsEnabled = actionsEnabled,
                listRequestActive = listRequestActive,
                listIsStale = listIsStale,
                listErrorCategory = listErrorCategory,
                onEvent = onEvent,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        val canSubmit =
            RunSubmissionState(
                latestRun = state.latestRun,
                activeRuns = state.activeRuns,
                isSubmissionPending = state.isSending || listRequestActive,
            ).canSubmit
        if (displayedMessages.isEmpty()) {
            Text(text = "No messages in this Session.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The summary travels with the transcript, so a short pane or a large font
                // scale scrolls it instead of pushing the composer and Send out of reach.
                item {
                    SessionDetailSummary(
                        state = state,
                        mutation = mutation,
                        actionsEnabled = actionsEnabled,
                        listRequestActive = listRequestActive,
                        listIsStale = listIsStale,
                        listErrorCategory = listErrorCategory,
                        onEvent = onEvent,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(
                    items = displayedMessages,
                    key = { message -> message.id },
                ) { message ->
                    SessionMessageContent(
                        message = message,
                        retryEnabled =
                            actionsEnabled &&
                                canSubmit &&
                                !state.isRefreshing &&
                                mutation?.pendingAction == null &&
                                !state.isSending &&
                                !state.isReconciliationInProgress &&
                                !state.hasUnresolvedSubmission &&
                                !listRequestActive,
                        onRetry = { runId -> onEvent(EntryUiEvent.RetryRunClicked(runId)) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        val composerEnabled = !state.isRefreshing && mutation?.pendingAction == null
        val sendEnabled =
            composerEnabled &&
                !state.isReconciliationInProgress &&
                state.latestRunState != RunPresentationState.UNCERTAIN &&
                state.sendErrorCategory != MessageSendErrorCategory.UNCERTAIN &&
                !state.hasUnresolvedSubmission &&
                canSubmit &&
                state.composerText.isNotBlank()
        OutlinedTextField(
            value = state.composerText,
            onValueChange = { onEvent(EntryUiEvent.ComposerTextChanged(it)) },
            label = { Text("Message") },
            placeholder = { Text("Write a message") },
            modifier = Modifier.fillMaxWidth(),
            enabled = composerEnabled,
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions =
                KeyboardActions(
                    onSend = {
                        if (sendEnabled) onEvent(EntryUiEvent.SendMessageClicked)
                    },
                ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.SendMessageClicked) },
            enabled = sendEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                text =
                    when {
                        state.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN ||
                            state.hasUnresolvedSubmission -> "Refresh history to resolve"
                        state.sendErrorCategory != null -> "Try again"
                        else -> "Send"
                    },
            )
        }
        if (state.isSending) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sending message…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.sendErrorCategory?.let { category ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.safeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/**
 * The Session summary above the transcript: history refresh, state warnings, Session actions,
 * and Run information. It renders inside the transcript when one exists.
 */
@Composable
private fun SessionDetailSummary(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    actionsEnabled: Boolean,
    listRequestActive: Boolean,
    listIsStale: Boolean,
    listErrorCategory: SessionListErrorCategory?,
    onEvent: (EntryUiEvent) -> Unit,
) {
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.RefreshSessionsClicked) },
        enabled = !state.isRefreshing && !listRequestActive && mutation?.pendingAction == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Refresh history")
    }
    if (state.isRefreshing) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (state.isReconciliationInProgress) {
                    "Refreshing Run and Session state…"
                } else {
                    "Refreshing Session history…"
                },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.isStale || listIsStale) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (state.isStale) {
                    "The displayed Session history may be stale."
                } else {
                    "The displayed Session data may be stale."
                },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    state.errorCategory?.let { category ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = category.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    listErrorCategory?.let { category ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = category.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    SessionActionControls(
        session = state.session,
        mutation = mutation,
        enabled = actionsEnabled && mutation?.pendingAction == null,
        onEvent = onEvent,
    )
    state.latestRun?.let { run ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Latest Run: ${run.id.value}")
        state.latestRunState?.let { runState ->
            Text(
                text = "Run state: ${runState.label}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } ?: Text(text = "Run status: ${run.status.stableRunStatusLabel()}")
    }
}

@Composable
private fun SessionMessageContent(
    message: SessionMessageUiState,
    retryEnabled: Boolean,
    onRetry: (RunId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        message.role?.takeIf(String::isNotBlank)?.let { role ->
            Text(text = "Role: $role")
        }
        message.content
            ?.takeIf { message.failureSafeMessage == null && it.isNotBlank() }
            ?.let { content ->
                Text(text = content)
            }
        message.runId?.let { runId ->
            Text(text = "Run ID: ${runId.value}")
        }
        message.runState?.let { runState ->
            Text(
                text = "Run state: ${runState.label}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } ?: message.runStatus?.let { status ->
            Text(text = "Run status: ${status.stableRunStatusLabel()}")
        }
        if (message.isStreaming) {
            Text(
                text = "Streaming response…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (message.streamInterrupted) {
            Text(
                text = "Stream interrupted. Run result is uncertain.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        message.failureSafeMessage?.let { safeMessage ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = safeMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        var showTechnicalDetail by remember(message.id) { mutableStateOf(false) }
        message.failureTechnicalDetail?.let { technicalDetail ->
            TextButton(onClick = { showTechnicalDetail = !showTechnicalDetail }) {
                Text(text = if (showTechnicalDetail) "Hide technical detail" else "Show technical detail")
            }
            if (showTechnicalDetail) {
                Text(
                    text = technicalDetail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (message.retryAvailable) {
            message.runId?.let { runId ->
                OutlinedButton(
                    onClick = { onRetry(runId) },
                    enabled = retryEnabled,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Text(text = "Try again")
                }
            }
        }
        if (message.failureSafeMessage == null) {
            message.runResult?.let { result ->
                Text(text = "Run result: $result")
            }
        }
        message.timestamp?.let { timestamp ->
            Text(text = "Timestamp: ${formatGatewayTimestamp(timestamp)}")
        }
    }
}

private fun String.stableRunStatusLabel(): String =
    when (lowercase(Locale.ROOT)) {
        "queued" -> "Queued"
        "running", "starting", "in_progress" -> "Running"
        "completed", "succeeded" -> "Completed"
        "failed", "error" -> "Failed"
        "cancelled", "canceled" -> "Cancelled"
        else -> "Unavailable"
    }

private fun formatGatewayTimestamp(timestamp: java.time.Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(timestamp)

private fun SessionMutationAction.pendingMessage(): String =
    when (this) {
        SessionMutationAction.RENAME -> "Renaming Session…"
        SessionMutationAction.PIN -> "Pinning Session…"
        SessionMutationAction.UNPIN -> "Unpinning Session…"
        SessionMutationAction.DELETE -> "Deleting Session…"
    }
