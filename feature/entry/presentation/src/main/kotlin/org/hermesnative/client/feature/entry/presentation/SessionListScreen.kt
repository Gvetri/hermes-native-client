package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionListContent(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.openedSession?.let { openedSession ->
        SessionDetailContent(
            state = openedSession,
            onEvent = onEvent,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.showFirstUseGuidance) {
            Text(
                text = "Select a Session to open its Gateway history. Refresh to load the latest server state.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (state.isLoading) {
            LoadingSessionsContent()
        } else if (state.sessions.isEmpty()) {
            EmptySessionsContent()
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = state.sessions,
                    key = { session -> session.id.value },
                ) { session ->
                    SessionRow(
                        session = session,
                        enabled = !state.isUnavailable && state.openingSessionId == null,
                        onClick = { onEvent(EntryUiEvent.SessionClicked(session.id)) },
                    )
                }
            }
        }

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

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = { onEvent(EntryUiEvent.RefreshSessionsClicked) },
            enabled = !state.isLoading && !state.isRefreshing && state.openingSessionId == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = if (state.isUnavailable) "Try again" else "Refresh")
        }
    }
}

@Composable
private fun LoadingSessionsContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Loading Sessions…")
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
        Text(text = "The Gateway returned no Sessions. Create one in the Gateway, then refresh this list.")
    }
}

@Composable
private fun SessionRow(
    session: SessionItemUiState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
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
                )
                if (session.pinned) {
                    Text(
                        text = "Pinned",
                        style = MaterialTheme.typography.labelMedium,
                    )
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
}

@Composable
private fun SessionDetailContent(
    state: OpenSessionUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
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
        Spacer(modifier = Modifier.height(16.dp))
        if (state.messages.isEmpty()) {
            Text(text = "No messages in this Session.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = state.messages,
                    key = { index, message -> message.id ?: "message-$index" },
                ) { _, message ->
                    message.content?.takeIf(String::isNotBlank)?.let { content ->
                        Text(
                            text = content,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
