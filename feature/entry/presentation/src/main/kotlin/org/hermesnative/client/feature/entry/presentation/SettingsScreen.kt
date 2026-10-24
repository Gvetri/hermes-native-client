package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsContent(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LocalHermesDesignTokens.current.spacing.xl),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "App version ${state.clientVersion}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        SettingsNotificationControls(state.runStatusNotifications, onEvent)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))
        SettingsGatewayControls(state, onEvent)
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = { onEvent(EntryUiEvent.CloseSettingsClicked) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Back to Sessions")
        }
    }
}

@Composable
private fun SettingsNotificationControls(
    state: RunStatusNotificationsUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
                Modifier
                    .testTag("settings-run-status-notifications")
                    .semantics {
                        contentDescription = "Run status notifications"
                    },
        )
    }
}

@Composable
private fun SettingsGatewayControls(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val diagnosticsEnabled = state.sessionList?.hasPendingMutation != true
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.OpenLocalDiagnosticsClicked) },
        enabled = diagnosticsEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Local diagnostics")
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.ChangeGatewayCredentialClicked) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Change Gateway credential")
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text =
            "Clearing removes cached conversations from this device only. " +
                "Remote Sessions and Runs are not deleted, and diagnostic files you already exported are not removed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.ClearHistoryCacheClicked) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings-clear-history-cache"),
    ) {
        Text(text = "Clear cached conversations")
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.RemoveGatewayConnectionClicked) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Remove Gateway Connection")
    }
}

@Composable
internal fun SettingsBackHandling(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    when {
        state.isRemoveConfirmationOpen ->
            ShellBackHandler { onEvent(EntryUiEvent.CancelRemoveGatewayConnectionClicked) }
        state.isClearHistoryCacheConfirmationOpen ->
            ShellBackHandler { onEvent(EntryUiEvent.CancelClearHistoryCacheClicked) }
        state.isChangingCredential ->
            ShellBackHandler { onEvent(EntryUiEvent.CancelGatewayCredentialChangeClicked) }
        else -> ShellBackHandler { onEvent(EntryUiEvent.CloseSettingsClicked) }
    }
}
