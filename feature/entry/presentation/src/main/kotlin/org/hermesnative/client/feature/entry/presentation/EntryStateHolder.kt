package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException

sealed interface EntryUiEvent {
    data object AddGatewayConnectionClicked : EntryUiEvent

    data class EndpointChanged(
        val value: String,
    ) : EntryUiEvent

    data class BearerCredentialChanged(
        val value: String,
    ) : EntryUiEvent

    data object VerifyGatewayConnectionClicked : EntryUiEvent

    data object TryAgainClicked : EntryUiEvent
}

enum class EntryErrorCategory(
    val safeMessage: String,
) {
    INVALID_ADDRESS("Invalid Gateway address. Enter one HTTPS Gateway endpoint."),
    SECURE_CONNECTION_FAILED("Secure connection failed. Check the Gateway certificate and hostname."),
    AUTHENTICATION_FAILED("Authentication failed. Check the Gateway credential."),
    REQUIRED_FEATURE_UNAVAILABLE("Required feature unavailable. This Gateway does not support the client contract."),
    GATEWAY_REQUEST_FAILED("Gateway request failed. Try again."),
}

data class EntryUiState(
    val title: String,
    val supportingText: String,
    val actionLabel: String,
    val connectionSetupRequested: Boolean = false,
    val endpoint: String = "",
    val bearerCredential: String = "",
    val isVerifying: Boolean = false,
    val isConnected: Boolean = false,
    val errorCategory: EntryErrorCategory? = null,
)

class EntryStateHolder(
    initialState: EntryState,
    private val verifyGatewayConnection: VerifyGatewayConnection? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _uiState = MutableStateFlow(initialState.toUiState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()
    private var verificationJob: Job? = null

    fun onEvent(event: EntryUiEvent) {
        when (event) {
            EntryUiEvent.AddGatewayConnectionClicked -> showConnectionSetup()
            is EntryUiEvent.EndpointChanged -> updateEndpoint(event.value)
            is EntryUiEvent.BearerCredentialChanged -> updateBearerCredential(event.value)
            EntryUiEvent.VerifyGatewayConnectionClicked,
            EntryUiEvent.TryAgainClicked,
            -> verifyConnection()
        }
    }

    fun close() {
        verificationJob?.cancel()
        scope.cancel()
    }

    private fun showConnectionSetup() {
        if (_uiState.value.isConnected) return
        _uiState.value = _uiState.value.connectionSetupState()
    }

    private fun updateEndpoint(value: String) {
        val state = _uiState.value
        if (state.isVerifying || state.isConnected) return
        _uiState.value = state.copy(endpoint = value, errorCategory = null)
    }

    private fun updateBearerCredential(value: String) {
        val state = _uiState.value
        if (state.isVerifying || state.isConnected) return
        _uiState.value = state.copy(bearerCredential = value, errorCategory = null)
    }

    private fun verifyConnection() {
        val verifier = verifyGatewayConnection ?: return
        val state = _uiState.value
        if (!state.connectionSetupRequested || state.isVerifying || state.isConnected) return

        verificationJob?.cancel()
        _uiState.value = state.copy(isVerifying = true, errorCategory = null)
        verificationJob =
            scope.launch {
                try {
                    verifier.execute(
                        endpoint = state.endpoint,
                        bearerCredential = state.bearerCredential,
                    )
                    _uiState.value =
                        _uiState.value.copy(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isVerifying = false,
                            isConnected = true,
                            errorCategory = null,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: GatewayException) {
                    showFailure(error.category.toUserFacingCategory())
                } catch (_: Exception) {
                    showFailure(EntryErrorCategory.GATEWAY_REQUEST_FAILED)
                }
            }
    }

    private fun showFailure(category: EntryErrorCategory) {
        _uiState.value =
            _uiState.value.copy(
                isVerifying = false,
                errorCategory = category,
            )
    }
}

private fun EntryState.toUiState(): EntryUiState =
    if (isGatewayConnectionConfigured || configuredEndpoint != null) {
        EntryUiState(
            title = "Verify your Hermes Gateway",
            supportingText = "Enter the bearer credential to verify the saved HTTPS endpoint.",
            actionLabel = "Verify Gateway Connection",
            connectionSetupRequested = true,
            endpoint = configuredEndpoint.orEmpty(),
        )
    } else {
        EntryUiState(
            title = "Connect to a Hermes Gateway",
            supportingText = "Use an existing compatible gateway. This app does not run Hermes on your device.",
            actionLabel = "Add Gateway Connection",
        )
    }

private fun EntryUiState.connectionSetupState(): EntryUiState =
    copy(
        title = "Verify a Hermes Gateway",
        supportingText = "Enter one profile-specific HTTPS endpoint and bearer credential.",
        actionLabel = "Verify Gateway Connection",
        connectionSetupRequested = true,
        errorCategory = null,
    )

private fun GatewayErrorCategory.toUserFacingCategory(): EntryErrorCategory =
    when (this) {
        GatewayErrorCategory.INVALID_ADDRESS -> EntryErrorCategory.INVALID_ADDRESS
        GatewayErrorCategory.SECURE_CONNECTION_FAILED -> EntryErrorCategory.SECURE_CONNECTION_FAILED
        GatewayErrorCategory.AUTHENTICATION_FAILED -> EntryErrorCategory.AUTHENTICATION_FAILED
        GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE -> EntryErrorCategory.REQUIRED_FEATURE_UNAVAILABLE
        GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
        GatewayErrorCategory.INVALID_RESPONSE,
        -> EntryErrorCategory.GATEWAY_REQUEST_FAILED
    }
