package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EntryScreen(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        ) {
            val sessionList = state.sessionList
            if (state.localDiagnostics.isOpen) {
                LocalDiagnosticsContent(
                    state = state.localDiagnostics,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (sessionList != null && !state.isChangingCredential) {
                SessionShell(
                    state = sessionList,
                    runStatusNotifications = state.runStatusNotifications,
                    onEvent = onEvent,
                    modifier = Modifier.fillMaxSize().padding(LocalHermesDesignTokens.current.spacing.xl),
                )
            } else {
                ConnectionContent(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun ConnectionContent(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val showConnectionForm = state.isChangingCredential || (state.connectionSetupRequested && !state.isConnected)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (showConnectionForm) Modifier.imePadding() else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(LocalHermesDesignTokens.current.spacing.xl),
        verticalArrangement = if (showConnectionForm) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = if (showConnectionForm) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (!showConnectionForm) {
            Text(
                text = "Hermes Native Client",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = if (showConnectionForm) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = state.supportingText,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = if (showConnectionForm) TextAlign.Start else TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        when {
            state.isChangingCredential -> GatewayConnectionForm(state = state, onEvent = onEvent)
            state.isConnected -> ConnectedGatewayContent(onEvent)
            state.connectionSetupRequested ->
                GatewayConnectionForm(
                    state = state,
                    onEvent = onEvent,
                )
            else ->
                Button(
                    onClick = { onEvent(EntryUiEvent.AddGatewayConnectionClicked) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                ) {
                    Text(text = state.actionLabel)
                }
        }
    }
}

@Composable
private fun GatewayConnectionForm(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    var credentialVisible by remember(state.isChangingCredential) { mutableStateOf(false) }
    OutlinedTextField(
        value = state.endpoint,
        onValueChange = { onEvent(EntryUiEvent.EndpointChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isVerifying && !state.isChangingCredential,
        label = { Text("Gateway HTTPS endpoint") },
        supportingText = { Text("Example: https://gateway.example") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.bearerCredential,
        onValueChange = { onEvent(EntryUiEvent.BearerCredentialChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isVerifying,
        label = { Text("Bearer credential") },
        singleLine = true,
        visualTransformation = if (credentialVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(
                onClick = { credentialVisible = !credentialVisible },
                enabled = !state.isVerifying,
                modifier =
                    Modifier.semantics {
                        contentDescription = if (credentialVisible) "Hide credential" else "Show credential"
                    },
            ) {
                Text(text = if (credentialVisible) "Hide" else "Show")
            }
        },
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = state.saveCredential,
            onCheckedChange = { onEvent(EntryUiEvent.SaveCredentialChanged(it)) },
            enabled = !state.isVerifying,
        )
        Text(text = "Save securely on this device")
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = {
            onEvent(
                if (state.errorCategory == null) {
                    EntryUiEvent.VerifyGatewayConnectionClicked
                } else {
                    EntryUiEvent.TryAgainClicked
                },
            )
        },
        enabled = !state.isVerifying,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
    ) {
        Text(text = if (state.errorCategory == null) state.actionLabel else "Try again")
    }
    if (state.isChangingCredential) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.CancelGatewayCredentialChangeClicked) },
            enabled = !state.isVerifying,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Cancel")
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (state.isGatewayConnectionConfigured || state.isConnected) {
        OutlinedButton(
            onClick = { onEvent(EntryUiEvent.RemoveGatewayConnectionClicked) },
            enabled = !state.isVerifying,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = "Remove Gateway Connection")
        }
    }
    if (state.isVerifying) {
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator(
            modifier =
                Modifier.semantics {
                    contentDescription = "Verifying Gateway connection"
                },
        )
        Text(
            text = "Verifying Gateway connection…",
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

@Composable
private fun ConnectedGatewayContent(onEvent: (EntryUiEvent) -> Unit) {
    Text(
        text = "Connected to Gateway",
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.ChangeGatewayCredentialClicked) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Change Gateway credential")
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.RemoveGatewayConnectionClicked) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Remove Gateway Connection")
    }
}
