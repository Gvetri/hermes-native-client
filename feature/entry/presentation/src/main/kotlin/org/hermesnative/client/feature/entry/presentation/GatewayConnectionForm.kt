package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun GatewayConnectionForm(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    var credentialVisible by remember(state.isChangingCredential) { mutableStateOf(false) }
    GatewayEndpointField(state = state, onEvent = onEvent)
    Spacer(modifier = Modifier.height(12.dp))
    GatewayBearerCredentialField(
        state = state,
        onEvent = onEvent,
        credentialVisible = credentialVisible,
        onToggleVisibility = { credentialVisible = !credentialVisible },
    )
    SaveCredentialRow(state = state, onEvent = onEvent)
    Spacer(modifier = Modifier.height(16.dp))
    VerifyConnectionButton(state = state, onEvent = onEvent)
    GatewayCredentialActions(state = state, onEvent = onEvent)
    VerifyingIndicator(state = state)
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
private fun GatewayEndpointField(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
}

@Composable
private fun GatewayBearerCredentialField(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
    credentialVisible: Boolean,
    onToggleVisibility: () -> Unit,
) {
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
                onClick = onToggleVisibility,
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
}

@Composable
private fun SaveCredentialRow(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
}

@Composable
private fun VerifyConnectionButton(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
}

@Composable
private fun GatewayCredentialActions(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
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
}

@Composable
private fun VerifyingIndicator(state: EntryUiState) {
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
}
