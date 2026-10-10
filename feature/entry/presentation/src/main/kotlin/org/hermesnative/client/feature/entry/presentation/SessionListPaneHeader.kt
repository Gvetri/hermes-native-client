package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionListPaneHeader(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SessionPaneTitleRow(state = state, onEvent = onEvent)
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

        SessionSearchControls(
            state = state,
            onEvent = onEvent,
            onSearchFocusChanged = onSearchFocusChanged,
        )
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
private fun SessionPaneTitleRow(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
}

@Composable
private fun SessionSearchControls(
    state: SessionListUiState,
    onEvent: (EntryUiEvent) -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
) {
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
}
