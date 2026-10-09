package org.hermesnative.client.feature.entry.presentation

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionListPane(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
    selectedSessionId: SessionId? = null,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    var searchFocused by remember { mutableStateOf(false) }
    val imeVisible =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.isImeVisible
        } else {
            rememberLegacyImeVisibility()
        }
    val renameInputOpen = state.sessionMutations.values.any { it.rename != null }
    val paneModifier = if (imeVisible && (searchFocused || renameInputOpen)) modifier.imePadding() else modifier
    BoxWithConstraints(modifier = paneModifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pinnedHeaderMaxHeight = panePinnedHeaderMaxHeight(maxHeight)
        var pinnedHeaderHeight by remember { mutableStateOf(0.dp) }
        val pinnedTailMaxHeight = paneTailMaxHeight(maxHeight - pinnedHeaderHeight)
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = pinnedHeaderMaxHeight)
                        .onSizeChanged { pinnedHeaderHeight = with(density) { it.height.toDp() } }
                        .verticalScroll(rememberScrollState()),
            ) {
                SessionListPaneHeader(
                    state = state,
                    onEvent = onEvent,
                    onSearchFocusChanged = { searchFocused = it },
                )
            }
            val listState = rememberLazyListState()
            LaunchedEffect(state.searchQuery) {
                if (state.searchQuery.isEmpty()) listState.scrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("session-list"),
                verticalArrangement = Arrangement.Top,
            ) {
                items(
                    items = state.sessions.filter { it.id in state.sessionMutations && it !in state.visibleSessions },
                    key = { session -> "management-${session.id.value}" },
                ) { session ->
                    Text(text = "Session action outside current search", style = MaterialTheme.typography.labelLarge)
                    Text(text = session.title, modifier = Modifier.semantics { heading() })
                    SessionActionControls(
                        session = session,
                        mutation = state.sessionMutations[session.id],
                        enabled = state.allowsSessionMutation(),
                        onEvent = onEvent,
                    )
                    Spacer(modifier = Modifier.height(spacing.s))
                }
                if (state.visibleSessions.isEmpty()) {
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
                        items = state.visibleSessions,
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

private const val IME_VISIBLE_HEIGHT_DIVISOR = 6

@Composable
private fun rememberLegacyImeVisibility(): Boolean {
    val view = LocalView.current
    var imeVisible by remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        val visibleFrame = Rect()
        val rootView = view.rootView
        val listener =
            ViewTreeObserver.OnGlobalLayoutListener {
                view.getWindowVisibleDisplayFrame(visibleFrame)
                val rootHeight = rootView.height
                imeVisible =
                    rootHeight > 0 &&
                    rootHeight - visibleFrame.height() > rootHeight / IME_VISIBLE_HEIGHT_DIVISOR
            }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return imeVisible
}

@Composable
private fun SessionListPaneHeader(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Sessions",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(
                onClick = { onEvent(EntryUiEvent.ChangeGatewayCredentialClicked) },
                enabled = state.createSession == null,
            ) {
                Text(text = "Change credential")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.RemoveGatewayConnectionClicked) },
            enabled = state.createSession == null,
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
            modifier = Modifier.fillMaxWidth().onFocusChanged { onSearchFocusChanged(it.isFocused) },
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
                    state.sessionMutations.isEmpty() &&
                    state.createSession == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Create Session")
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SessionListStateContent(state: SessionListUiState) {
    if ((state.isLoading || state.isSearching) && state.visibleSessions.isEmpty()) {
        LoadingSessionsContent(if (state.isSearching) "Searching Sessions…" else "Loading Sessions…")
    } else if (state.visibleSessions.isEmpty() && (state.isUnavailable || state.isStale)) {
        if (state.searchQuery.isNotBlank()) {
            SearchUnavailableContent()
        } else {
            SessionsUnavailableContent()
        }
    } else if (state.searchQuery.isNotBlank() && state.visibleSessions.isEmpty()) {
        NoSearchResultsContent()
    } else if (state.visibleSessions.isEmpty()) {
        EmptySessionsContent()
    }
}

@Composable
private fun SessionListPaginationFooterIfAvailable(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    if (
        state.visibleSessions.isEmpty() &&
        !state.isLoading &&
        !state.isSearching &&
        state.nextOffset != null
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
                    "The displayed Session data may be stale. " +
                        "Gateway actions are unavailable until the connection recovers."
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

@Composable
private fun SessionListPaneControls(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    runStatusNotifications: RunStatusNotificationsUiState,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onEvent(EntryUiEvent.RefreshSessionListClicked) },
            enabled =
                !state.isLoading &&
                    !state.isRefreshing &&
                    state.openingSessionId == null &&
                    !state.hasPendingMutation &&
                    state.createSession == null &&
                    state.openedSession?.isRefreshing != true &&
                    state.openedSession?.isReconciliationInProgress != true,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(text = if (state.isUnavailable) "Try again" else "Refresh")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = { onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked) },
            enabled = state.createSession == null && !state.hasPendingMutation,
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
        ) {
            Text(text = "Local diagnostics")
        }
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
        state.nextOffset != null ->
            Button(
                onClick = { onEvent(EntryUiEvent.LoadMoreSessionsClicked) },
                enabled =
                    !state.isUnavailable &&
                        !state.isRefreshing &&
                        state.sessionMutations.isEmpty() &&
                        state.createSession == null &&
                        state.openedSession?.isRefreshing != true &&
                        state.openedSession?.isReconciliationInProgress != true,
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
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
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
    showMenu: Boolean = true,
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
                if (showMenu) {
                    SessionActionMenu(
                        session = session,
                        enabled = enabled && mutation?.pendingAction == null,
                        onEvent = onEvent,
                    )
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
                val retryEvent =
                    when (mutation?.retryAction) {
                        SessionMutationAction.PIN -> EntryUiEvent.PinSessionClicked(session.id)
                        SessionMutationAction.UNPIN -> EntryUiEvent.UnpinSessionClicked(session.id)
                        SessionMutationAction.RENAME, SessionMutationAction.DELETE, null -> null
                    }
                retryEvent?.let { event ->
                    OutlinedButton(
                        onClick = { onEvent(event) },
                        enabled = enabled && mutation?.pendingAction == null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = "Try again")
                    }
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

private fun paneTailMaxHeight(available: Dp): Dp = (available - 120.dp).coerceAtLeast(minOf(48.dp, available))

private fun panePinnedHeaderMaxHeight(available: Dp): Dp {
    return (available - 120.dp - 48.dp).coerceAtLeast(minOf(24.dp, available))
}

@Composable
private fun SessionDetailHeader(state: OpenSessionUiState) {
    Text(
        text = state.session.title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    state.session.preview?.let { preview ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = preview, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val canSubmit =
        RunSubmissionState(
            latestRun = state.latestRun,
            activeRuns = state.activeRuns,
            isSubmissionPending = state.isSending || listRequestActive,
        ).canSubmit
    val retryEnabled =
        actionsEnabled &&
            canSubmit &&
            !state.isRefreshing &&
            mutation?.pendingAction == null &&
            !state.isSending &&
            !state.isReconciliationInProgress &&
            !state.hasUnresolvedSubmission &&
            !listRequestActive
    val paneScrollState = rememberScrollState()
    val tailScrollState = rememberScrollState()
    val transcriptListState = rememberLazyListState()
    val newestMessageItemIndex = displayedMessages.size
    val followNewestMessages = rememberTranscriptFollow(transcriptListState)
    var transcriptPositionPending by remember(state.session.id) { mutableStateOf(true) }
    val streamedResponseContent = state.activeResponse?.content
    LaunchedEffect(state.session.id, newestMessageItemIndex, streamedResponseContent) {
        if (newestMessageItemIndex == 0) return@LaunchedEffect
        if (transcriptPositionPending) {
            transcriptListState.pinNewestItemEnd(newestMessageItemIndex)
            transcriptPositionPending = false
        } else if (followNewestMessages) {
            transcriptListState.pinNewestItemEnd(newestMessageItemIndex)
        }
    }
    val showMutationContext = mutation?.rename != null || mutation?.delete != null || mutation?.errorCategory != null
    LaunchedEffect(state.session.id, showMutationContext) {
        if (showMutationContext && displayedMessages.isNotEmpty()) transcriptListState.scrollToItem(0)
    }
    Column(
        modifier =
            if (displayedMessages.isEmpty()) {
                modifier.fillMaxSize().verticalScroll(paneScrollState)
            } else {
                modifier.fillMaxSize()
            },
        verticalArrangement = Arrangement.Top,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = { onEvent(EntryUiEvent.ReturnToSessionListClicked) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = "Back to Sessions")
            }
            SessionActionMenu(
                session = state.session,
                enabled =
                    actionsEnabled &&
                        mutation?.pendingAction == null &&
                        mutation?.rename == null &&
                        mutation?.delete == null,
                onEvent = onEvent,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (displayedMessages.isEmpty()) {
            SessionDetailHeader(state = state)
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (displayedMessages.isEmpty()) {
            SessionDetailSummary(
                state = state,
                mutation = mutation,
                actionsEnabled = actionsEnabled,
                retryEnabled = retryEnabled,
                listRequestActive = listRequestActive,
                listIsStale = listIsStale,
                listErrorCategory = listErrorCategory,
                onEvent = onEvent,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (displayedMessages.isEmpty()) {
            Text(text = "No messages in this Session.")
        } else {
            LazyColumn(
                state = transcriptListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SessionDetailHeader(state = state)
                    Spacer(modifier = Modifier.height(12.dp))
                    SessionDetailSummary(
                        state = state,
                        mutation = mutation,
                        actionsEnabled = actionsEnabled,
                        retryEnabled = retryEnabled,
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
                        retryEnabled = retryEnabled,
                        onRetry = { runId -> onEvent(EntryUiEvent.RetryRunClicked(runId)) },
                    )
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val tailMaxHeight = paneTailMaxHeight(maxHeight)
            Column(
                modifier =
                    Modifier.testTag("conversation-composer-tail").then(
                        if (displayedMessages.isEmpty()) {
                            Modifier
                        } else {
                            Modifier.fillMaxWidth()
                                .heightIn(max = tailMaxHeight)
                                .verticalScroll(tailScrollState)
                        },
                    ),
            ) {
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
                    maxLines = 5,
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
    }
}

private suspend fun LazyListState.pinNewestItemEnd(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { item -> item.index == index }) {
        scrollToItem(index)
    }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val itemSize = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == index }?.size ?: return
    scrollToItem(index, (itemSize - viewportHeight).coerceAtLeast(0))
}

/**
 * Whether the transcript should follow its newest content. The follow stays on while the
 * newest content's end is visible, turns off when the reader scrolls it out of view, and
 * returns once the transcript shows the newest content again, so a reader who scrolled up
 * is not pulled away from what they are reading.
 */
@Composable
internal fun rememberTranscriptFollow(listState: LazyListState): Boolean {
    val follow by
        remember(listState) {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val newestItem = layoutInfo.visibleItemsInfo.lastOrNull()
                layoutInfo.totalItemsCount == 0 ||
                    (
                        newestItem != null &&
                            newestItem.index == layoutInfo.totalItemsCount - 1 &&
                            newestItem.offset.toLong() + newestItem.size.toLong() <=
                            layoutInfo.viewportEndOffset.toLong()
                    )
            }
        }
    return follow
}

@Composable
private fun SessionDetailSummary(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    actionsEnabled: Boolean,
    retryEnabled: Boolean,
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
        showMenu = false,
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
        if (state.latestRunRetryAvailable) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onEvent(EntryUiEvent.RetryRunClicked(run.id)) },
                enabled = retryEnabled,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(text = "Try again")
            }
        }
    }
}

@Composable
internal fun SessionMessageContent(
    message: SessionMessageUiState,
    retryEnabled: Boolean,
    onRetry: (RunId) -> Unit,
    actions: MessageActions = rememberMessageActions(),
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        message.role?.takeIf(String::isNotBlank)?.let { role ->
            Text(text = "Role: $role")
        }
        message.content
            ?.takeIf { message.failureSafeMessage == null && it.isNotBlank() }
            ?.let { content ->
                MarkdownContent(markdown = content, actions = actions)
                Spacer(modifier = Modifier.height(4.dp))
                MessageActionControls(content = content, actions = actions)
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

@Composable
private fun MessageActionControls(
    content: String,
    actions: MessageActions,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        OutlinedButton(
            onClick = { actions.copy(content) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(text = "Copy message")
        }
        OutlinedButton(
            onClick = { actions.share(content) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Text(text = "Share message")
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
