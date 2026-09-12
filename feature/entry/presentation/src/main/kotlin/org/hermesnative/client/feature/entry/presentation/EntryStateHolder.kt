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
import org.hermesnative.client.feature.entry.application.DeleteSession
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.PinSession
import org.hermesnative.client.feature.entry.application.RenameSession
import org.hermesnative.client.feature.entry.application.UnpinSession
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

    data class RenameSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class RenameSessionTitleChanged(
        val sessionId: SessionId,
        val value: String,
    ) : EntryUiEvent

    data class ConfirmRenameSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class CancelRenameSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class PinSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class UnpinSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class DeleteSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class ConfirmDeleteSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

    data class CancelDeleteSessionClicked(
        val sessionId: SessionId,
    ) : EntryUiEvent

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
    private val mutationJobs = mutableMapOf<SessionId, Job>()

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
            is EntryUiEvent.RenameSessionClicked -> showRenameSession(event.sessionId)
            is EntryUiEvent.RenameSessionTitleChanged -> updateRenameSessionTitle(event.sessionId, event.value)
            is EntryUiEvent.ConfirmRenameSessionClicked -> confirmRenameSession(event.sessionId)
            is EntryUiEvent.CancelRenameSessionClicked -> cancelRenameSession(event.sessionId)
            is EntryUiEvent.PinSessionClicked -> pinSession(event.sessionId)
            is EntryUiEvent.UnpinSessionClicked -> unpinSession(event.sessionId)
            is EntryUiEvent.DeleteSessionClicked -> showDeleteSession(event.sessionId)
            is EntryUiEvent.ConfirmDeleteSessionClicked -> confirmDeleteSession(event.sessionId)
            is EntryUiEvent.CancelDeleteSessionClicked -> cancelDeleteSession(event.sessionId)
            is EntryUiEvent.SessionClicked -> openSession(event.sessionId)
            EntryUiEvent.ReturnToSessionListClicked -> returnToSessionList()
        }
    }

    fun close() {
        verificationJob?.cancel()
        sessionJob?.cancel()
        val mutationJobsToCancel =
            synchronized(sessionRequestLock) {
                mutationJobs.values.toList().also { mutationJobs.clear() }
            }
        mutationJobsToCancel.forEach(Job::cancel)
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
                if (sessionList.sessionMutations.isNotEmpty()) return

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
        synchronized(sessionRequestLock) {
            val sessionList = _uiState.value.sessionList ?: return
            if (
                (sessionList.isLoading && !sessionList.isSearching) ||
                sessionList.isRefreshing ||
                sessionList.openingSessionId != null ||
                sessionList.createSession != null ||
                sessionList.hasPendingMutation
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
                    sessionList.createSession != null ||
                    sessionList.sessionMutations.isNotEmpty()
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
            sessionList.createSession != null ||
            sessionList.sessionMutations.isNotEmpty()
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

    private fun showRenameSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (!current.allowsSessionMutation()) return
            val mutation = current.sessionMutations[sessionId]
            if (mutation?.pendingAction != null || mutation?.delete != null) return
            val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            SessionMutationUiState(
                                                rename = SessionRenameUiState(session.title),
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun updateRenameSessionTitle(
        sessionId: SessionId,
        value: String,
    ) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            val rename = mutation.rename ?: return
            if (mutation.pendingAction != null || rename.isSubmitting) return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                rename =
                                                    rename.copy(
                                                        titleDraft = value,
                                                        errorCategory = null,
                                                    ),
                                                errorCategory = null,
                                                retryAction = null,
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun cancelRenameSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            if (mutation.pendingAction != null) return
            val remaining = mutation.copy(rename = null)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                if (remaining.delete == null && remaining.errorCategory == null) {
                                    current.sessionMutations - sessionId
                                } else {
                                    current.sessionMutations + (sessionId to remaining)
                                },
                        ),
                )
        }
    }

    private fun confirmRenameSession(sessionId: SessionId) {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return
                if (!current.allowsSessionMutation()) return
                val mutation = current.sessionMutations[sessionId] ?: return
                val rename = mutation.rename ?: return
                if (mutation.pendingAction != null || rename.isSubmitting) return
                val title = rename.titleDraft.trim()
                val validationError = rename.titleDraft.validateRenameTitle()
                if (validationError != null) {
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    sessionMutations =
                                        current.sessionMutations +
                                            (
                                                sessionId to
                                                    mutation.copy(
                                                        rename = rename.copy(errorCategory = validationError),
                                                    )
                                            ),
                                ),
                        )
                    return
                }
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                sessionMutations =
                                    current.sessionMutations +
                                        (
                                            sessionId to
                                                mutation.copy(
                                                    rename =
                                                        rename.copy(
                                                            isSubmitting = true,
                                                            errorCategory = null,
                                                        ),
                                                    pendingAction = SessionMutationAction.RENAME,
                                                    errorCategory = null,
                                                    retryAction = null,
                                                )
                                        ),
                            ),
                    )
                createMutationJob(sessionId) {
                    try {
                        val confirmed = RenameSession(gateway).execute(sessionId, title)
                        applyConfirmedSession(confirmed)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showRenameFailure(sessionId)
                    }
                }
            }
        job.start()
    }

    private fun showRenameFailure(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            val rename = mutation.rename ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                rename =
                                                    rename.copy(
                                                        isSubmitting = false,
                                                        errorCategory = SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                                                    ),
                                                pendingAction = null,
                                                retryAction = SessionMutationAction.RENAME,
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun createMutationJob(
        sessionId: SessionId,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    synchronized(sessionRequestLock) {
                        if (mutationJobs[sessionId] === job) {
                            mutationJobs.remove(sessionId)
                        }
                    }
                }
            }
        synchronized(sessionRequestLock) {
            mutationJobs[sessionId] = job
        }
        return job
    }

    private fun applyConfirmedSession(session: Session) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (current.sessions.none { it.id == session.id }) return
            val updated = session.toSessionItemUiState()
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions =
                                current.sessions
                                    .map { item -> if (item.id == session.id) updated else item }
                                    .orderedSessions(),
                            openedSession =
                                current.openedSession
                                    ?.takeIf { it.session.id == session.id }
                                    ?.copy(session = updated)
                                    ?: current.openedSession,
                            sessionMutations = current.sessionMutations - session.id,
                        ),
                )
        }
    }

    private fun pinSession(sessionId: SessionId) {
        changeSessionPin(sessionId, pinned = true)
    }

    private fun unpinSession(sessionId: SessionId) {
        changeSessionPin(sessionId, pinned = false)
    }

    private fun changeSessionPin(
        sessionId: SessionId,
        pinned: Boolean,
    ) {
        val gateway = sessionGateway ?: return
        val action = if (pinned) SessionMutationAction.PIN else SessionMutationAction.UNPIN
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return
                if (!current.allowsSessionMutation()) return
                if (current.sessions.none { it.id == sessionId }) return
                val mutation = current.sessionMutations[sessionId] ?: SessionMutationUiState()
                if (mutation.pendingAction != null || mutation.rename != null || mutation.delete != null) return
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                sessionMutations =
                                    current.sessionMutations +
                                        (
                                            sessionId to
                                                mutation.copy(
                                                    pendingAction = action,
                                                    errorCategory = null,
                                                    retryAction = null,
                                                )
                                        ),
                            ),
                    )
                createMutationJob(sessionId) {
                    try {
                        val result =
                            if (pinned) {
                                PinSession(gateway).execute(sessionId)
                            } else {
                                UnpinSession(gateway).execute(sessionId)
                            }
                        applyConfirmedPin(result.sessionId, result.pinned)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showPinFailure(sessionId, action)
                    }
                }
            }
        job.start()
    }

    private fun applyConfirmedPin(
        sessionId: SessionId,
        pinned: Boolean,
    ) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (current.sessions.none { it.id == sessionId }) return
            val mutation = current.sessionMutations[sessionId] ?: return
            val updatedSessions =
                current.sessions
                    .map { item -> if (item.id == sessionId) item.copy(pinned = pinned) else item }
                    .orderedSessions()
            val remainingMutation =
                mutation.copy(
                    pendingAction = null,
                    errorCategory = null,
                    retryAction = null,
                )
            val shouldRefresh = current.nextCursor != null
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions = updatedSessions,
                            openedSession =
                                current.openedSession?.let { opened ->
                                    if (opened.session.id == sessionId) {
                                        opened.copy(session = opened.session.copy(pinned = pinned))
                                    } else {
                                        opened
                                    }
                                },
                            sessionMutations = mutationMapAfter(sessionId, remainingMutation, current.sessionMutations),
                        ),
                )
            if (shouldRefresh) {
                sessionGateway?.let { gateway ->
                    startFirstPageLoad(
                        gateway = gateway,
                        query = current.searchQuery,
                        isRefreshing = true,
                        isSearching = false,
                        preserveSessions = true,
                    )
                }
            }
        }
    }

    private fun showPinFailure(
        sessionId: SessionId,
        action: SessionMutationAction,
    ) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                pendingAction = null,
                                                errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                                                retryAction = action,
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun showDeleteSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (!current.allowsSessionMutation()) return
            if (current.sessions.none { it.id == sessionId }) return
            val mutation = current.sessionMutations[sessionId] ?: SessionMutationUiState()
            if (mutation.pendingAction != null || mutation.rename != null) return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                delete = SessionDeleteUiState(),
                                                errorCategory = null,
                                                retryAction = null,
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun cancelDeleteSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            if (mutation.pendingAction != null) return
            val remaining = mutation.copy(delete = null, errorCategory = null, retryAction = null)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations = mutationMapAfter(sessionId, remaining, current.sessionMutations),
                        ),
                )
        }
    }

    private fun confirmDeleteSession(sessionId: SessionId) {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return
                if (!current.allowsSessionMutation()) return
                val mutation = current.sessionMutations[sessionId] ?: return
                val delete = mutation.delete ?: return
                if (mutation.pendingAction != null || delete.isSubmitting) return
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                sessionMutations =
                                    current.sessionMutations +
                                        (
                                            sessionId to
                                                mutation.copy(
                                                    delete = delete.copy(isSubmitting = true),
                                                    pendingAction = SessionMutationAction.DELETE,
                                                    errorCategory = null,
                                                    retryAction = null,
                                                )
                                        ),
                            ),
                    )
                createMutationJob(sessionId) {
                    try {
                        DeleteSession(gateway).execute(sessionId)
                        removeConfirmedSession(sessionId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showDeleteFailure(sessionId)
                    }
                }
            }
        job.start()
    }

    private fun showDeleteFailure(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId] ?: return
            val delete = mutation.delete ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                delete = delete.copy(isSubmitting = false),
                                                pendingAction = null,
                                                errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                                                retryAction = SessionMutationAction.DELETE,
                                            )
                                    ),
                        ),
                )
        }
    }

    private fun removeConfirmedSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val shouldRefresh = current.nextCursor != null
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions = current.sessions.filterNot { it.id == sessionId },
                            openedSession = current.openedSession?.takeUnless { it.session.id == sessionId },
                            sessionMutations = current.sessionMutations - sessionId,
                        ),
                )
            if (shouldRefresh) {
                sessionGateway?.let { gateway ->
                    startFirstPageLoad(
                        gateway = gateway,
                        query = current.searchQuery,
                        isRefreshing = true,
                        isSearching = false,
                        preserveSessions = true,
                    )
                }
            }
        }
    }

    private fun mutationMapAfter(
        sessionId: SessionId,
        mutation: SessionMutationUiState,
        mutations: Map<SessionId, SessionMutationUiState>,
    ): Map<SessionId, SessionMutationUiState> =
        if (
            mutation.rename == null &&
            mutation.delete == null &&
            mutation.pendingAction == null &&
            mutation.errorCategory == null &&
            mutation.retryAction == null
        ) {
            mutations - sessionId
        } else {
            mutations + (sessionId to mutation)
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
                                sessionMutations =
                                    if (preserveSessions) {
                                        unsentRenameDrafts(current.sessionMutations)
                                    } else {
                                        emptyMap()
                                    },
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
                    sessionMutations =
                        retainRenameDrafts(
                            latest.sessionMutations,
                            page.sessions.map { it.id },
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
                    sessionList.isLoading ||
                    sessionList.isRefreshing ||
                    sessionList.isSearching ||
                    sessionList.isUnavailable ||
                    sessionList.isLoadingMore ||
                    sessionList.hasPendingMutation ||
                    sessionList.openingSessionId != null ||
                    sessionList.createSession != null
                ) {
                    return
                }
                if (
                    sessionList.sessions.none { it.id == sessionId } ||
                    sessionList.sessionMutations[sessionId]?.pendingAction != null
                ) {
                    return
                }

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
                            val authoritativeSession = openedSession.session.toSessionItemUiState()
                            current.copy(
                                sessions =
                                    current.sessions
                                        .map { item ->
                                            if (item.id == sessionId) authoritativeSession else item
                                        }
                                        .orderedSessions(),
                                sessionMutations = retainRenameDraft(current.sessionMutations, sessionId),
                                openingSessionId = null,
                                isStale = false,
                                isUnavailable = false,
                                openedSession =
                                    OpenSessionUiState(
                                        session = authoritativeSession,
                                        messages = openedSession.history.messages.map { it.toSessionMessageUiState() },
                                    ),
                                errorCategory = null,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: GatewayException) {
                        showSessionOpenFailure(request.generation, request.query, sessionId)
                    } catch (_: Exception) {
                        showSessionOpenFailure(request.generation, request.query, sessionId)
                    }
                }
            }
        job.start()
    }

    private fun showSessionOpenFailure(
        requestGeneration: Long,
        query: String,
        sessionId: SessionId,
    ) {
        updateCurrentSessionRequest(requestGeneration, query) { current ->
            current.copy(
                sessionMutations = retainRenameDraft(current.sessionMutations, sessionId),
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

private fun unsentRenameDrafts(mutations: Map<SessionId, SessionMutationUiState>): Map<SessionId, SessionMutationUiState> =
    mutations.mapNotNull { (sessionId, mutation) ->
        mutation.rename
            ?.takeIf {
                !it.isSubmitting &&
                    mutation.pendingAction == null &&
                    mutation.errorCategory == null &&
                    mutation.retryAction == null
            }
            ?.let { rename ->
                sessionId to
                    SessionMutationUiState(
                        rename = SessionRenameUiState(titleDraft = rename.titleDraft),
                    )
            }
    }.toMap()

private fun retainRenameDrafts(
    mutations: Map<SessionId, SessionMutationUiState>,
    sessionIds: List<SessionId>,
): Map<SessionId, SessionMutationUiState> = unsentRenameDrafts(mutations).filterKeys { it in sessionIds }

private fun retainRenameDraft(
    mutations: Map<SessionId, SessionMutationUiState>,
    sessionId: SessionId,
): Map<SessionId, SessionMutationUiState> =
    unsentRenameDrafts(mutations)
        .filterKeys { it == sessionId }
        .let { draft ->
            if (draft.isEmpty()) {
                mutations - sessionId
            } else {
                mutations + draft
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

private fun String.validateRenameTitle(): SessionRenameErrorCategory? =
    when {
        trim().isEmpty() -> SessionRenameErrorCategory.EMPTY_TITLE
        any(Char::isISOControl) -> SessionRenameErrorCategory.CONTROL_CHARACTER
        else -> null
    }
