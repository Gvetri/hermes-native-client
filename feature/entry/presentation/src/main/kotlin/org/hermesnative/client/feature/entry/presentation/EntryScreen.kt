package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        if (state.sessionList != null) {
            SessionListContent(
                state = state.sessionList,
                onEvent = onEvent,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Hermes Native Client",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.supportingText,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(24.dp))
                when {
                    state.isConnected -> ConnectedGatewayContent()
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
    }
}

@Composable
private fun GatewayConnectionForm(
    state: EntryUiState,
    onEvent: (EntryUiEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.endpoint,
        onValueChange = { onEvent(EntryUiEvent.EndpointChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isVerifying,
        label = { Text("Gateway HTTPS endpoint") },
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.bearerCredential,
        onValueChange = { onEvent(EntryUiEvent.BearerCredentialChanged(it)) },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isVerifying,
        label = { Text("Bearer credential") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
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
private fun ConnectedGatewayContent() {
    Text(
        text = "Connected to Gateway",
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
}
