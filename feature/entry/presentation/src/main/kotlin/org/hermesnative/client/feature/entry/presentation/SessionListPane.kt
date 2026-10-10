package org.hermesnative.client.feature.entry.presentation

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.SessionId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionListPane(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
    selectedSessionId: SessionId? = null,
) {
    var searchFocused by remember { mutableStateOf(false) }
    val imeVisible = paneImeVisible()
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
            SessionListLazyBody(
                state = state,
                onEvent = onEvent,
                selectedSessionId = selectedSessionId,
                listState = listState,
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun paneImeVisible(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowInsets.isImeVisible
    } else {
        rememberLegacyImeVisibility()
    }
}

@Composable
private fun ColumnScope.SessionListLazyBody(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    selectedSessionId: SessionId?,
    listState: LazyListState,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("session-list"),
        verticalArrangement = Arrangement.Top,
    ) {
        sessionListItems(
            state = state,
            onEvent = onEvent,
            selectedSessionId = selectedSessionId,
            spacing = spacing,
        )
    }
}

private fun LazyListScope.sessionListItems(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    selectedSessionId: SessionId?,
    spacing: HermesSpacing,
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
                row =
                    SessionRowUiState(
                        session = session,
                        mutation = state.sessionMutations[session.id],
                        selected = session.id == selectedSessionId,
                        enabled = state.allowsSessionMutation(),
                        onClick = { onEvent(EntryUiEvent.SessionClicked(session.id)) },
                    ),
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

private const val IME_VISIBLE_HEIGHT_DIVISOR = 6

internal fun paneTailMaxHeight(available: Dp): Dp = (available - 120.dp).coerceAtLeast(minOf(48.dp, available))

private fun panePinnedHeaderMaxHeight(available: Dp): Dp {
    return (available - 120.dp - 48.dp).coerceAtLeast(minOf(24.dp, available))
}
