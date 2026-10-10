package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionListStateContent(state: SessionListUiState) {
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
internal fun SessionListPaginationFooterIfAvailable(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val firstPageFinished = state.visibleSessions.isEmpty() && !state.isLoading && !state.isSearching
    if (firstPageFinished && state.nextOffset != null) {
        SessionPaginationFooter(state = state, onEvent = onEvent)
    }
}

@Composable
internal fun SessionListStatusTexts(state: SessionListUiState) {
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
internal fun SessionPaginationFooter(
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
internal fun LoadingSessionsContent(message: String) {
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
internal fun NoSearchResultsContent() {
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
internal fun SearchUnavailableContent() {
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
internal fun SessionsUnavailableContent() {
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
internal fun EmptySessionsContent() {
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
