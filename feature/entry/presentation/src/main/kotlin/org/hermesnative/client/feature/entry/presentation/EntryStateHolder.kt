package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.application.CreateSession
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest

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

    data class SessionSearchQueryChanged(
        val value: String,
    ) : EntryUiEvent

    data object ClearSessionSearchClicked : EntryUiEvent

    data object LoadMoreSessionsClicked : EntryUiEvent

    data object CreateSessionClicked : EntryUiEvent

    data class CreateSessionTitleChanged(
        val value: String,
    ) : EntryUiEvent

    data object ConfirmCreateSessionClicked : EntryUiEvent

    data object CancelCreateSessionClicked : EntryUiEvent

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

private const val SESSION_SEARCH_DEBOUNCE_MILLIS = 300L

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
    private val sessionRequestLock = Any()
    private var sessionRequestGeneration = 0L

    fun onEvent(event: EntryUiEvent) {
        when (event) {
            EntryUiEvent.AddGatewayConnectionClicked -> showConnectionSetup()
            is EntryUiEvent.EndpointChanged -> updateEndpoint(event.value)
            is EntryUiEvent.BearerCredentialChanged -> updateBearerCredential(event.value)
            EntryUiEvent.VerifyGatewayConnectionClicked,
            EntryUiEvent.TryAgainClicked,
            -> verifyConnection()
            EntryUiEvent.RefreshSessionsClicked -> refreshSessions()
            is EntryUiEvent.SessionSearchQueryChanged -> updateSearchQuery(event.value)
            EntryUiEvent.ClearSessionSearchClicked -> clearSearch()
            EntryUiEvent.LoadMoreSessionsClicked -> loadMoreSessions()
            EntryUiEvent.CreateSessionClicked -> showCreateSession()
            is EntryUiEvent.CreateSessionTitleChanged -> updateCreateSessionTitle(event.value)
            EntryUiEvent.ConfirmCreateSessionClicked -> confirmCreateSession()
            EntryUiEvent.CancelCreateSessionClicked -> cancelCreateSession()
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
        val sessionList = _uiState.value.sessionList ?: return
        startFirstPageLoad(
            gateway = gateway,
            query = sessionList.searchQuery,
            isRefreshing = false,
            isSearching = false,
            preserveSessions = false,
        )
    }

    private fun updateSearchQuery(value: String) {
        val job =
            synchronized(sessionRequestLock) {
                val state = _uiState.value
                val sessionList = state.sessionList ?: return
                if (sessionList.searchQuery == value) return
                if (sessionList.createSession?.isSubmitting == true) return

                val gateway = sessionGateway
                val requestGeneration = beginSessionRequest()
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                sessions = sessionList.sessions,
                                searchQuery = value,
                                nextCursor = null,
                                isLoading = gateway != null,
                                isLoadingMore = false,
                                isSearching = gateway != null,
                                isRefreshing = false,
                                isStale = false,
                                isUnavailable = false,
                                openingSessionId = null,
                                openedSession = null,
                                errorCategory = null,
                            ),
                    )
                gateway?.let {
                    val request = SessionRequestContext(requestGeneration, value, cursor = null)
                    createSessionJob {
                        delay(SESSION_SEARCH_DEBOUNCE_MILLIS)
                        loadFirstPage(it, request, preserveSessions = false)
                    }
                }
            }
        job?.start()
    }

    private fun clearSearch() {
        val sessionList = _uiState.value.sessionList ?: return
        if (sessionList.searchQuery.isEmpty()) return
        updateSearchQuery("")
    }

    private fun refreshSessions() {
        val gateway = sessionGateway ?: return
        val state = _uiState.value
        val sessionList = state.sessionList ?: return
        if (
            (sessionList.isLoading && !sessionList.isSearching) ||
            sessionList.isRefreshing ||
            sessionList.openingSessionId != null ||
            sessionList.createSession != null
        ) {
            return
        }

        startFirstPageLoad(
            gateway = gateway,
            query = sessionList.searchQuery,
            isRefreshing = true,
            isSearching = false,
            preserveSessions = true,
        )
    }

    private fun loadMoreSessions() {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val state = _uiState.value
                val sessionList = state.sessionList ?: return
                val cursor = sessionList.nextCursor ?: return
                if (
                    sessionList.isLoading ||
                    sessionList.isRefreshing ||
                    sessionList.isSearching ||
                    sessionList.isLoadingMore ||
                    sessionList.isUnavailable ||
                    sessionList.openingSessionId != null ||
                    sessionList.createSession != null
                ) {
                    return
                }

                val query = sessionList.searchQuery
                val requestGeneration = beginSessionRequest()
                val request = SessionRequestContext(requestGeneration, query, cursor)
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                isLoadingMore = true,
                                errorCategory = null,
                            ),
                    )
                createSessionJob {
                    try {
                        val page = LoadSessionList(gateway).execute(sessionListRequest(request.query, request.cursor))
                        updateCurrentSessionRequest(request.generation, request.query, request.cursor) { current ->
                            current.copy(
                                sessions =
                                    mergeSessions(
                                        current.sessions,
                                        page.sessions.map { it.toSessionItemUiState() },
                                    ),
                                nextCursor = page.nextCursor,
                                isLoadingMore = false,
                                isStale = false,
                                isUnavailable = false,
                                errorCategory = null,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: GatewayException) {
                        showSessionListFailure(request.generation, request.query, request.cursor, preserveSessions = true)
                    } catch (_: Exception) {
                        showSessionListFailure(request.generation, request.query, request.cursor, preserveSessions = true)
                    }
                }
            }
        job.start()
    }

    private fun showCreateSession() {
        val state = _uiState.value
        val sessionList = state.sessionList ?: return
        if (
            sessionList.isLoading ||
            sessionList.isRefreshing ||
            sessionList.isLoadingMore ||
            sessionList.openingSessionId != null ||
            sessionList.isUnavailable ||
            sessionList.createSession != null
        ) {
            return
        }
        _uiState.value =
            state.copy(
                sessionList =
                    sessionList.copy(
                        createSession = SessionCreationUiState(),
                        errorCategory = null,
                    ),
            )
    }

    private fun updateCreateSessionTitle(value: String) {
        val current = _uiState.value.sessionList ?: return
        val creation = current.createSession ?: return
        if (creation.isSubmitting) return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        createSession =
                            creation.copy(
                                titleDraft = value,
                                errorCategory = null,
                            ),
                    ),
            )
    }

    private fun cancelCreateSession() {
        val current = _uiState.value.sessionList ?: return
        if (current.createSession?.isSubmitting == true) return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        createSession = null,
                        errorCategory = null,
                    ),
            )
    }

    private fun confirmCreateSession() {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val state = _uiState.value
                val sessionList = state.sessionList ?: return
                val creation = sessionList.createSession ?: return
                if (creation.isSubmitting || sessionList.isUnavailable) return

                val title = creation.titleDraft.trim().takeIf(String::isNotEmpty)
                val query = sessionList.searchQuery
                val requestGeneration = beginSessionRequest()
                val request =
                    CreateSessionRequest(
                        context = SessionRequestContext(requestGeneration, query, cursor = null),
                        title = title,
                    )
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                createSession =
                                    creation.copy(
                                        isSubmitting = true,
                                        errorCategory = null,
                                    ),
                            ),
                    )
                createSessionJob(gateway, request)
            }
        job.start()
    }

    private fun createSessionJob(
        gateway: SessionGatewayPort,
        request: CreateSessionRequest,
    ): Job =
        createSessionJob {
            var createdSession: Session? = null
            try {
                createdSession = CreateSession(gateway).execute(request.title)
                val openedSession = OpenSession(gateway).execute(createdSession.id)
                val refreshedPage =
                    try {
                        LoadSessionList(gateway).execute(sessionListRequest(request.context.query, cursor = null))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                val openedSessionUiState =
                    OpenSessionUiState(
                        session = openedSession.session.toSessionItemUiState(),
                        messages = openedSession.history.messages.map { it.toSessionMessageUiState() },
                    )
                updateCurrentSessionRequest(request.context.generation, request.context.query) { current ->
                    current.copy(
                        sessions =
                            refreshedPage?.let { page ->
                                mergeSessions(
                                    emptyList(),
                                    page.sessions.map { it.toSessionItemUiState() },
                                )
                            } ?: if (request.context.query.isBlank()) {
                                mergeSession(current.sessions, openedSession.session)
                            } else {
                                current.sessions
                            },
                        nextCursor = refreshedPage?.nextCursor,
                        isLoading = false,
                        isRefreshing = false,
                        isSearching = false,
                        isLoadingMore = false,
                        isStale = refreshedPage == null,
                        isUnavailable = refreshedPage == null,
                        errorCategory =
                            if (refreshedPage == null) {
                                SessionListErrorCategory.GATEWAY_UNAVAILABLE
                            } else {
                                null
                            },
                        createSession = null,
                        openedSession = openedSessionUiState,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: GatewayException) {
                if (createdSession == null) {
                    showCreateSessionFailure(request.context.generation, request.context.query)
                } else {
                    showCreatedSessionFailure(request.context.generation, request.context.query, createdSession)
                }
            } catch (_: Exception) {
                if (createdSession == null) {
                    showCreateSessionFailure(request.context.generation, request.context.query)
                } else {
                    showCreatedSessionFailure(request.context.generation, request.context.query, createdSession)
                }
            }
        }

    private fun showCreateSessionFailure(
        requestGeneration: Long,
        query: String,
    ) {
        updateCurrentSessionRequest(requestGeneration, query) { current ->
            current.createSession?.let { creation ->
                current.copy(
                    createSession =
                        creation.copy(
                            isSubmitting = false,
                            errorCategory = SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED,
                        ),
                )
            } ?: current
        }
    }

    private fun showCreatedSessionFailure(
        requestGeneration: Long,
        query: String,
        createdSession: Session,
    ) {
        updateCurrentSessionRequest(requestGeneration, query) { current ->
            current.copy(
                sessions =
                    if (query.isBlank()) {
                        mergeSession(current.sessions, createdSession)
                    } else {
                        current.sessions
                    },
                isLoading = false,
                isRefreshing = false,
                isSearching = false,
                isLoadingMore = false,
                isStale = true,
                isUnavailable = true,
                errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
                createSession = null,
            )
        }
    }

    private fun mergeSession(
        sessions: List<SessionItemUiState>,
        session: Session,
    ): List<SessionItemUiState> = mergeSessions(sessions, listOf(session.toSessionItemUiState()))

    private fun mergeSessions(
        existing: List<SessionItemUiState>,
        incoming: List<SessionItemUiState>,
    ): List<SessionItemUiState> = (existing + incoming).associateBy { it.id }.values.toList().orderedSessions()

    private fun startFirstPageLoad(
        gateway: SessionGatewayPort,
        query: String,
        isRefreshing: Boolean,
        isSearching: Boolean,
        preserveSessions: Boolean,
    ) {
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return
                val requestGeneration = beginSessionRequest()
                val request = SessionRequestContext(requestGeneration, query, cursor = null)
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                sessions = if (preserveSessions) current.sessions else emptyList(),
                                isLoading = !isRefreshing,
                                isRefreshing = isRefreshing,
                                isSearching = isSearching,
                                isLoadingMore = false,
                                isStale = if (preserveSessions) current.isStale else false,
                                isUnavailable = false,
                                errorCategory = null,
                                nextCursor = if (preserveSessions) current.nextCursor else null,
                            ),
                    )
                createSessionJob {
                    loadFirstPage(gateway, request, preserveSessions)
                }
            }
        job.start()
    }

    private suspend fun loadFirstPage(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        preserveSessions: Boolean,
    ) {
        try {
            val page = LoadSessionList(gateway).execute(sessionListRequest(request.query, request.cursor))
            updateCurrentSessionRequest(request.generation, request.query) { latest ->
                latest.copy(
                    sessions =
                        mergeSessions(
                            emptyList(),
                            page.sessions.map { it.toSessionItemUiState() },
                        ),
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    isRefreshing = false,
                    isSearching = false,
                    isLoadingMore = false,
                    isStale = false,
                    isUnavailable = false,
                    errorCategory = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showSessionListFailure(
                request.generation,
                request.query,
                cursor = null,
                preserveSessions = preserveSessions,
            )
        } catch (_: Exception) {
            showSessionListFailure(
                request.generation,
                request.query,
                cursor = null,
                preserveSessions = preserveSessions,
            )
        }
    }

    private fun sessionListRequest(
        query: String,
        cursor: String?,
    ): SessionListRequest =
        SessionListRequest(
            cursor = cursor,
            search = query.trim().takeIf(String::isNotEmpty),
        )

    private fun beginSessionRequest(): Long {
        return synchronized(sessionRequestLock) {
            sessionRequestGeneration += 1
            sessionJob?.cancel()
            sessionRequestGeneration
        }
    }

    private fun createSessionJob(block: suspend CoroutineScope.() -> Unit): Job =
        synchronized(sessionRequestLock) {
            scope.launch(start = CoroutineStart.LAZY, block = block).also { sessionJob = it }
        }

    private fun updateCurrentSessionRequest(
        requestGeneration: Long,
        query: String,
        cursor: String? = null,
        transform: (SessionListUiState) -> SessionListUiState,
    ): Boolean =
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList
            if (
                current == null ||
                requestGeneration != sessionRequestGeneration ||
                current.searchQuery != query ||
                (cursor != null && current.nextCursor != cursor)
            ) {
                false
            } else {
                _uiState.value = _uiState.value.copy(sessionList = transform(current))
                true
            }
        }

    private fun showSessionListFailure(
        requestGeneration: Long,
        query: String,
        cursor: String?,
        preserveSessions: Boolean,
    ) {
        updateCurrentSessionRequest(requestGeneration, query, cursor) { current ->
            current.copy(
                isLoading = false,
                isRefreshing = false,
                isSearching = false,
                isLoadingMore = false,
                isStale = true,
                isUnavailable = !preserveSessions || current.sessions.isEmpty(),
                errorCategory = SessionListErrorCategory.GATEWAY_UNAVAILABLE,
            )
        }
    }

    private fun openSession(sessionId: SessionId) {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val state = _uiState.value
                val sessionList = state.sessionList ?: return
                if (
                    sessionList.isUnavailable ||
                    sessionList.isLoadingMore ||
                    sessionList.openingSessionId != null
                ) {
                    return
                }
                if (sessionList.sessions.none { it.id == sessionId }) return

                val query = sessionList.searchQuery
                val requestGeneration = beginSessionRequest()
                val request = SessionRequestContext(requestGeneration, query, cursor = null)
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                openingSessionId = sessionId,
                                errorCategory = null,
                            ),
                    )
                createSessionJob {
                    try {
                        val openedSession = OpenSession(gateway).execute(sessionId)
                        updateCurrentSessionRequest(request.generation, request.query) { current ->
                            current.copy(
                                openingSessionId = null,
                                openedSession =
                                    OpenSessionUiState(
                                        session = openedSession.session.toSessionItemUiState(),
                                        messages = openedSession.history.messages.map { it.toSessionMessageUiState() },
                                    ),
                                errorCategory = null,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: GatewayException) {
                        showSessionOpenFailure(request.generation, request.query)
                    } catch (_: Exception) {
                        showSessionOpenFailure(request.generation, request.query)
                    }
                }
            }
        job.start()
    }

    private fun showSessionOpenFailure(
        requestGeneration: Long,
        query: String,
    ) {
        updateCurrentSessionRequest(requestGeneration, query) { current ->
            current.copy(
                openingSessionId = null,
                isStale = true,
                isUnavailable = true,
                errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
            )
        }
    }

    private fun returnToSessionList() {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (current.createSession != null) {
                if (!current.createSession.isSubmitting) {
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    createSession = null,
                                    errorCategory = null,
                                ),
                        )
                }
                return
            }
            beginSessionRequest()
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
}

private data class SessionRequestContext(
    val generation: Long,
    val query: String,
    val cursor: String?,
)

private data class CreateSessionRequest(
    val context: SessionRequestContext,
    val title: String?,
)

private fun List<SessionItemUiState>.orderedSessions(): List<SessionItemUiState> = filter { it.pinned } + filterNot { it.pinned }

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
