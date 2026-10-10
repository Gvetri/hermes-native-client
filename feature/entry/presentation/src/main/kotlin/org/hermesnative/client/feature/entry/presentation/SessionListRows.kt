package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionRow(
    row: SessionRowUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    val session = row.session
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = row.onClick,
            enabled = row.enabled && row.mutation?.pendingAction == null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics { this.selected = row.selected },
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
                    SessionRowBadges(selected = row.selected, pinned = session.pinned, spacing = spacing.s)
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
            mutation = row.mutation,
            enabled = row.enabled,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun SessionRowBadges(
    selected: Boolean,
    pinned: Boolean,
    spacing: Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (pinned) {
            Text(
                text = "Pinned",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun SessionActionControls(
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
                SessionMutationRetryControl(
                    session = session,
                    mutation = mutation,
                    enabled = enabled,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun SessionMutationRetryControl(
    session: SessionItemUiState,
    mutation: SessionMutationUiState?,
    enabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
        SessionMutationProgress(visible = state.isSubmitting, label = "Renaming Session…", topSpacing = 8.dp)
        SessionMutationErrorMessage(message = state.errorCategory?.safeMessage, topSpacing = 8.dp)
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
        SessionMutationProgress(visible = state.isSubmitting, label = "Deleting Session…", topSpacing = 8.dp)
        SessionMutationErrorMessage(message = errorCategory?.safeMessage, topSpacing = 8.dp)
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
        SessionMutationProgress(visible = state.isSubmitting, label = "Creating Session…", topSpacing = 16.dp)
        SessionMutationErrorMessage(message = state.errorCategory?.safeMessage, topSpacing = 16.dp)
    }
}

@Composable
private fun SessionMutationProgress(
    visible: Boolean,
    label: String,
    topSpacing: Dp,
) {
    if (!visible) return
    Spacer(modifier = Modifier.height(topSpacing))
    CircularProgressIndicator(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        text = label,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SessionMutationErrorMessage(
    message: String?,
    topSpacing: Dp,
) {
    if (message == null) return
    Spacer(modifier = Modifier.height(topSpacing))
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

private fun SessionMutationAction.pendingMessage(): String =
    when (this) {
        SessionMutationAction.RENAME -> "Renaming Session…"
        SessionMutationAction.PIN -> "Pinning Session…"
        SessionMutationAction.UNPIN -> "Unpinning Session…"
        SessionMutationAction.DELETE -> "Deleting Session…"
    }
