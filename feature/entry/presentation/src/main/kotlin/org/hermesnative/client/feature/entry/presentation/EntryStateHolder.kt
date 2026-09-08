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
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId

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

    data object RefreshSessionsClicked : EntryUiEvent

    data class SessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data object ReturnToSessionListClicked : EntryUiEvent
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
    val sessionList: SessionListUiState? = null,
)

class EntryStateHolder(
    initialState: EntryState,
    private val verifyGatewayConnection: VerifyGatewayConnection? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sessionGatewayFactory: ((endpoint: String, bearerCredential: String) -> SessionGatewayPort)? = null,
) {
    private val _uiState = MutableStateFlow(initialState.toUiState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()
    private var verificationJob: Job? = null
    private var sessionJob: Job? = null
    private var sessionGateway: SessionGatewayPort? = null

    fun onEvent(event: EntryUiEvent) {
        when (event) {
            EntryUiEvent.AddGatewayConnectionClicked -> showConnectionSetup()
            is EntryUiEvent.EndpointChanged -> updateEndpoint(event.value)
            is EntryUiEvent.BearerCredentialChanged -> updateBearerCredential(event.value)
            EntryUiEvent.VerifyGatewayConnectionClicked,
            EntryUiEvent.TryAgainClicked,
            -> verifyConnection()
            EntryUiEvent.RefreshSessionsClicked -> refreshSessions()
            is EntryUiEvent.SessionClicked -> openSession(event.sessionId)
            EntryUiEvent.ReturnToSessionListClicked -> returnToSessionList()
        }
    }

    fun close() {
        verificationJob?.cancel()
        sessionJob?.cancel()
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
                    val gateway =
                        sessionGatewayFactory?.invoke(
                            state.endpoint,
                            state.bearerCredential,
                        )
                    sessionGateway = gateway
                    _uiState.value =
                        _uiState.value.copy(
                            title = "Gateway connected",
                            supportingText = "The Gateway contract was verified successfully.",
                            actionLabel = "Connected",
                            isVerifying = false,
                            isConnected = true,
                            errorCategory = null,
                            sessionList =
                                gateway?.let {
                                    SessionListUiState(
                                        isLoading = true,
                                        showFirstUseGuidance = true,
                                    )
                                },
                        )
                    gateway?.let(::loadInitialSessions)
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

    private fun loadInitialSessions(gateway: SessionGatewayPort) {
        sessionJob?.cancel()
        sessionJob =
            scope.launch {
                loadSessions(gateway)
            }
    }

    private fun refreshSessions() {
        val gateway = sessionGateway ?: return
        val state = _uiState.value
        val sessionList = state.sessionList ?: return
        if (sessionList.isLoading || sessionList.isRefreshing || sessionList.openingSessionId != null) return

        _uiState.value =
            state.copy(
                sessionList =
                    sessionList.copy(
                        isRefreshing = true,
                        isStale = false,
                        isUnavailable = false,
                        errorCategory = null,
                    ),
            )
        sessionJob?.cancel()
        sessionJob =
            scope.launch {
                loadSessions(gateway)
            }
    }

    private suspend fun loadSessions(gateway: SessionGatewayPort) {
        try {
            val page = LoadSessionList(gateway).execute()
            val orderedSessions = page.sessions.filter { it.pinned } + page.sessions.filterNot { it.pinned }
            val current = _uiState.value.sessionList ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions = orderedSessions.map { it.toSessionItemUiState() },
                            isLoading = false,
                            isRefreshing = false,
                            isStale = false,
                            isUnavailable = false,
                            errorCategory = null,
                        ),
                )
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showSessionListFailure()
        } catch (_: Exception) {
            showSessionListFailure()
        }
    }

    private fun showSessionListFailure() {
        val current = _uiState.value.sessionList ?: return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isStale = true,
                        isUnavailable = true,
                        errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
                    ),
            )
    }

    private fun openSession(sessionId: SessionId) {
        val gateway = sessionGateway ?: return
        val state = _uiState.value
        val sessionList = state.sessionList ?: return
        if (sessionList.isUnavailable || sessionList.openingSessionId != null) return
        if (sessionList.sessions.none { it.id == sessionId }) return

        _uiState.value =
            state.copy(
                sessionList =
                    sessionList.copy(
                        openingSessionId = sessionId,
                        errorCategory = null,
                    ),
            )
        sessionJob?.cancel()
        sessionJob =
            scope.launch {
                try {
                    val openedSession = OpenSession(gateway).execute(sessionId)
                    val current = _uiState.value.sessionList ?: return@launch
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    openingSessionId = null,
                                    openedSession =
                                        OpenSessionUiState(
                                            session = openedSession.session.toSessionItemUiState(),
                                            messages = openedSession.history.messages.map { it.toSessionMessageUiState() },
                                        ),
                                    errorCategory = null,
                                ),
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: GatewayException) {
                    showSessionOpenFailure()
                } catch (_: Exception) {
                    showSessionOpenFailure()
                }
            }
    }

    private fun showSessionOpenFailure() {
        val current = _uiState.value.sessionList ?: return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openingSessionId = null,
                        isStale = true,
                        isUnavailable = true,
                        errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
                    ),
            )
    }

    private fun returnToSessionList() {
        val current = _uiState.value.sessionList ?: return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession = null,
                        openingSessionId = null,
                        errorCategory = null,
                    ),
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
