package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.hermesnative.client.feature.entry.application.EntryState

sealed interface EntryUiEvent {
    data object AddGatewayConnectionClicked : EntryUiEvent
}

data class EntryUiState(
    val title: String,
    val supportingText: String,
    val actionLabel: String,
    val connectionSetupRequested: Boolean = false,
)

class EntryStateHolder(
    initialState: EntryState,
) {
    private val _uiState = MutableStateFlow(initialState.toUiState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()

    fun onEvent(event: EntryUiEvent) {
        when (event) {
            EntryUiEvent.AddGatewayConnectionClicked -> {
                _uiState.value = _uiState.value.copy(connectionSetupRequested = true)
            }
        }
    }
}

private fun EntryState.toUiState(): EntryUiState =
    if (isGatewayConnectionConfigured) {
        EntryUiState(
            title = "Gateway connection ready",
            supportingText = "Your Hermes Sessions will appear here.",
            actionLabel = "Open Sessions",
        )
    } else {
        EntryUiState(
            title = "Connect to a Hermes Gateway",
            supportingText = "Use an existing compatible gateway. This app does not run Hermes on your device.",
            actionLabel = "Add Gateway Connection",
        )
    }
