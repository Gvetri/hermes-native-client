package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.CreateSession
import org.hermesnative.client.feature.entry.application.DeleteSession
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.ObserveRun
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.application.PinSession
import org.hermesnative.client.feature.entry.application.ReconcileRun
import org.hermesnative.client.feature.entry.application.ReconcileSession
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.RenameSession
import org.hermesnative.client.feature.entry.application.SubmitMessage
import org.hermesnative.client.feature.entry.application.UnpinSession
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.AuthoritativeRunReconciliation
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventStateTransition
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunSubmissionState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.decideRunReconciliation
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.runs
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

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

    data class ComposerTextChanged(
        val value: String,
    ) : EntryUiEvent

    data object SendMessageClicked : EntryUiEvent

    data object RemoveGatewayConnectionClicked : EntryUiEvent
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
private const val DEFAULT_SEND_TIMEOUT_MILLIS = 30_000L
private const val UNCERTAIN_RUN_STATUS = "uncertain"
private const val UNCERTAIN_SEND_RUN_PREFIX = "uncertain-send:"

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
    private val runGatewayFactory: ((endpoint: String, bearerCredential: String) -> RunGatewayPort)? = null,
    private val removeGatewayConnectionUseCase: RemoveGatewayConnection? = null,
    private val onRunSubmissionCompleted: (() -> Unit)? = null,
    private val onRunSubmissionSettled: (() -> Unit)? = null,
    private val sendTimeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
) {
    init {
        require(sendTimeoutMillis > 0) { "sendTimeoutMillis must be positive." }
    }

    private val _uiState = MutableStateFlow(initialState.toUiState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()
    private var verificationJob: Job? = null
    private var sessionJob: Job? = null
    private var sessionGateway: SessionGatewayPort? = null
    private var runGateway: RunGatewayPort? = null
    private val sessionRequestLock = Any()
    private var sessionRequestGeneration = 0L
    private var connectionGeneration = 0L
    private val mutationJobs = mutableMapOf<SessionId, Job>()
    private val runJobs = mutableMapOf<SessionId, Job>()
    private val sessionDrafts = mutableMapOf<SessionId, String>()
    private val sessionSendErrors = mutableMapOf<SessionId, MessageSendErrorCategory>()
    private val pendingRunDrafts = mutableMapOf<SessionId, String>()
    private val sessionRuns = mutableMapOf<SessionId, List<Run>>()
    private val runObservationJobs = mutableMapOf<SessionId, Job>()
    private val runObservations = mutableMapOf<SessionId, RunEventObservation>()
    private val runObservationStates = mutableMapOf<SessionId, MutableMap<RunId, RunObservationState>>()
    private val uncertainSendRunIds = mutableMapOf<SessionId, RunId>()
    private val reconcilingSessions = mutableMapOf<SessionId, Long>()

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
            is EntryUiEvent.ComposerTextChanged -> updateComposerText(event.value)
            EntryUiEvent.SendMessageClicked -> sendMessage()
            EntryUiEvent.RemoveGatewayConnectionClicked -> removeGatewayConnection()
        }
    }

    fun close() {
        var observationsToClose: List<RunEventObservation> = emptyList()
        val jobsToCancel =
            synchronized(sessionRequestLock) {
                sessionRequestGeneration += 1
                connectionGeneration += 1
                sessionGateway = null
                runGateway = null
                val verificationJobToCancel = verificationJob
                val sessionJobToCancel = sessionJob
                verificationJob = null
                sessionJob = null
                val requestJobs = (mutationJobs.values + runJobs.values + runObservationJobs.values).toList()
                observationsToClose = runObservations.values.toList()
                mutationJobs.clear()
                runJobs.clear()
                runObservationJobs.clear()
                runObservations.clear()
                runObservationStates.clear()
                uncertainSendRunIds.clear()
                reconcilingSessions.clear()
                sessionDrafts.clear()
                sessionSendErrors.clear()
                pendingRunDrafts.clear()
                sessionRuns.clear()
                requestJobs + listOfNotNull(verificationJobToCancel, sessionJobToCancel)
            }
        jobsToCancel.forEach(Job::cancel)
        observationsToClose.forEach(RunEventObservation::close)
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
        val requestConnectionGeneration =
            synchronized(sessionRequestLock) {
                connectionGeneration.also {
                    _uiState.value = state.copy(isVerifying = true, errorCategory = null)
                }
            }
        verificationJob =
            scope.launch {
                try {
                    verifier.execute(
                        endpoint = state.endpoint,
                        bearerCredential = state.bearerCredential,
                    )
                    val gateway =
                        synchronized(sessionRequestLock) {
                            if (connectionGeneration != requestConnectionGeneration) {
                                null
                            } else {
                                val gateway =
                                    sessionGatewayFactory?.invoke(
                                        state.endpoint,
                                        state.bearerCredential,
                                    )
                                sessionGateway = gateway
                                runGateway =
                                    runGatewayFactory?.invoke(
                                        state.endpoint,
                                        state.bearerCredential,
                                    ) ?: (gateway as? RunGatewayPort)
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
                                gateway
                            }
                        }
                    gateway?.let(::loadInitialSessions)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: GatewayException) {
                    showFailure(error.category.toUserFacingCategory(), requestConnectionGeneration)
                } catch (_: Exception) {
                    showFailure(EntryErrorCategory.GATEWAY_REQUEST_FAILED, requestConnectionGeneration)
                }
            }
    }

    private fun showFailure(
        category: EntryErrorCategory,
        requestConnectionGeneration: Long,
    ) {
        synchronized(sessionRequestLock) {
            if (connectionGeneration == requestConnectionGeneration) {
                _uiState.value =
                    _uiState.value.copy(
                        isVerifying = false,
                        errorCategory = category,
                    )
            }
        }
    }

    private fun removeGatewayConnection() {
        removeGatewayConnectionUseCase?.execute()
        verificationJob?.cancel()
        sessionJob?.cancel()
        var observationsToClose: List<RunEventObservation> = emptyList()
        val jobsToCancel =
            synchronized(sessionRequestLock) {
                sessionRequestGeneration += 1
                connectionGeneration += 1
                sessionGateway = null
                runGateway = null
                sessionDrafts.clear()
                sessionSendErrors.clear()
                pendingRunDrafts.clear()
                sessionRuns.clear()
                observationsToClose = runObservations.values.toList()
                (mutationJobs.values + runJobs.values + runObservationJobs.values).toList().also {
                    mutationJobs.clear()
                    runJobs.clear()
                    runObservationJobs.clear()
                    runObservations.clear()
                    runObservationStates.clear()
                    uncertainSendRunIds.clear()
                    reconcilingSessions.clear()
                }
            }
        jobsToCancel.forEach(Job::cancel)
        observationsToClose.forEach(RunEventObservation::close)
        _uiState.value = EntryState().toUiState()
    }

    private fun loadInitialSessions(gateway: SessionGatewayPort) {
        val sessionList = _uiState.value.sessionList ?: return
        createFirstPageLoadJob(
            gateway = gateway,
            query = sessionList.searchQuery,
            isRefreshing = false,
            isSearching = false,
            preserveSessions = false,
        )?.start()
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
        val job =
            synchronized(sessionRequestLock) {
                val sessionList = _uiState.value.sessionList ?: return@synchronized null
                if (sessionList.openedSession != null) {
                    refreshOpenedSession(gateway)
                } else if (
                    (sessionList.isLoading && !sessionList.isSearching) ||
                    sessionList.isRefreshing ||
                    sessionList.openingSessionId != null ||
                    sessionList.createSession != null ||
                    sessionList.hasPendingMutation
                ) {
                    null
                } else {
                    createFirstPageLoadJob(
                        gateway = gateway,
                        query = sessionList.searchQuery,
                        isRefreshing = true,
                        isSearching = false,
                        preserveSessions = true,
                    )
                }
            }
        job?.start()
    }

    private fun refreshOpenedSession(gateway: SessionGatewayPort): Job? =
        synchronized(sessionRequestLock) {
            val state = _uiState.value
            val sessionList = state.sessionList ?: return@synchronized null
            val openedSession = sessionList.openedSession ?: return@synchronized null
            if (openedSession.isRefreshing || sessionList.hasActiveRequest) return@synchronized null

            val runIdToReconcile =
                sessionRuns[openedSession.session.id]
                    .orEmpty()
                    .latestActiveRun()
                    ?.id
                    ?: latestObservationState(openedSession.session.id, sessionRuns[openedSession.session.id].orEmpty())?.run?.id
            val requestGeneration = beginSessionRequest()
            val requestConnectionGeneration = connectionGeneration
            val request = SessionRequestContext(requestGeneration, sessionList.searchQuery, cursor = null)
            _uiState.value =
                state.copy(
                    sessionList =
                        sessionList.copy(
                            openedSession =
                                openedSession.copy(
                                    isRefreshing = true,
                                    errorCategory = null,
                                ),
                            errorCategory = null,
                        ),
                )
            createSessionJob {
                loadOpenedSession(
                    gateway = gateway,
                    request = request,
                    sessionId = openedSession.session.id,
                    requestConnectionGeneration = requestConnectionGeneration,
                    runIdToReconcile = runIdToReconcile,
                )
            }
        }

    private suspend fun loadOpenedSession(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        runIdToReconcile: RunId? = null,
    ) {
        try {
            val openedSession = OpenSession(gateway).execute(sessionId)
            val applied =
                updateCurrentSessionRequest(request.generation, request.query) { current ->
                    val previous = current.openedSession
                    if (previous == null) {
                        current
                    } else {
                        val authoritativeSession = openedSession.session.toSessionItemUiState()
                        val knownRuns = rememberSessionRuns(sessionId, openedSession)
                        val latestObservation = latestObservationState(sessionId, knownRuns)
                        current.copy(
                            sessions =
                                current.sessions
                                    .map { item -> if (item.id == sessionId) authoritativeSession else item }
                                    .orderedSessions(),
                            openedSession =
                                openedSession.toOpenSessionUiState(
                                    composerText = sessionDrafts[sessionId] ?: previous.composerText,
                                    sendErrorCategory = sessionSendErrors[sessionId],
                                    latestRun = knownRuns.latestRun(),
                                    activeRuns = knownRuns.activeRuns(),
                                    isSending = previous.isSending || runJobs.containsKey(sessionId),
                                    latestRunState = latestObservation?.state,
                                    activeResponse = latestObservation?.toSessionMessageUiState(),
                                    isRefreshing = previous.isRefreshing,
                                ),
                            errorCategory = null,
                        )
                    }
                }
            if (applied) {
                reconcileOpenedRun(
                    sessionId = sessionId,
                    requestSessionGeneration = request.generation,
                    requestConnectionGeneration = requestConnectionGeneration,
                    sessionGateway = gateway,
                    restartObservation = true,
                    runIdToReconcile = runIdToReconcile,
                    clearRefreshWhenNoRun = true,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showOpenedSessionFailure(request.generation, request.query)
        } catch (_: Exception) {
            showOpenedSessionFailure(request.generation, request.query)
        }
    }

    private suspend fun reconcileOpenedRun(
        sessionId: SessionId,
        requestSessionGeneration: Long,
        requestConnectionGeneration: Long,
        sessionGateway: SessionGatewayPort,
        restartObservation: Boolean,
        runIdToReconcile: RunId? = null,
        clearRefreshWhenNoRun: Boolean = false,
    ) {
        val run =
            synchronized(sessionRequestLock) {
                if (runIdToReconcile?.let { uncertainSendRunIds[sessionId] == it } == true) {
                    null
                } else {
                    val candidate =
                        sessionRuns[sessionId].orEmpty().lastOrNull { it.id == runIdToReconcile }
                            ?: runIdToReconcile?.let { runId -> observationStateFor(sessionId, runId)?.run }
                            ?: sessionRuns[sessionId].orEmpty().latestActiveRun()
                            ?: latestObservationState(sessionId, sessionRuns[sessionId].orEmpty())
                                ?.takeIf { state -> !state.state.isTerminal() }
                                ?.run
                    candidate?.takeUnless { it.isUncertainSendRun() }
                }
            }
        if (run == null) {
            if (clearRefreshWhenNoRun) {
                finishReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)
            }
            return
        }

        reconcileRun(
            sessionId = sessionId,
            runId = run.id,
            requestConnectionGeneration = requestConnectionGeneration,
            requestSessionGeneration = requestSessionGeneration,
            sessionGateway = sessionGateway,
        )
        if (
            restartObservation &&
            shouldReconcileCurrentSession(requestConnectionGeneration, requestSessionGeneration, sessionId)
        ) {
            synchronized(sessionRequestLock) {
                sessionRuns[sessionId].orEmpty().latestActiveRun()
            }?.let { activeRun -> startRunObservation(sessionId, activeRun) }
        }
    }

    private suspend fun reconcileRun(
        sessionId: SessionId,
        runId: RunId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        sessionGateway: SessionGatewayPort,
    ): AuthoritativeRunReconciliation? {
        if (!beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) return null
        return try {
            val runGateway =
                synchronized(sessionRequestLock) {
                    runGateway?.takeIf {
                        connectionGeneration == requestConnectionGeneration &&
                            sessionRequestGeneration == requestSessionGeneration &&
                            _uiState.value.sessionList?.openedSession?.session?.id == sessionId
                    }
                } ?: return null
            val result = ReconcileRun(runGateway, sessionGateway).execute(runId, sessionId)
            applyAuthoritativeRunReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration, result)
            result
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showRunReconciliationFailure(sessionId, runId, requestConnectionGeneration, requestSessionGeneration)
            null
        } catch (_: Exception) {
            showRunReconciliationFailure(sessionId, runId, requestConnectionGeneration, requestSessionGeneration)
            null
        } finally {
            finishReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)
        }
    }

    private fun beginReconciliation(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ): Boolean =
        synchronized(sessionRequestLock) {
            val opened = _uiState.value.sessionList?.openedSession
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration ||
                opened?.session?.id != sessionId ||
                reconcilingSessions[sessionId] == requestSessionGeneration
            ) {
                false
            } else {
                reconcilingSessions[sessionId] = requestSessionGeneration
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            _uiState.value.sessionList?.copy(
                                openedSession =
                                    opened.copy(
                                        isRefreshing = true,
                                        isReconciliationInProgress = true,
                                        errorCategory = null,
                                    ),
                            ),
                    )
                true
            }
        }

    private fun finishReconciliation(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ) {
        synchronized(sessionRequestLock) {
            if (reconcilingSessions[sessionId] == requestSessionGeneration) {
                reconcilingSessions.remove(sessionId)
            }
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return
            }
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    isRefreshing = false,
                                    isReconciliationInProgress = false,
                                ),
                        ),
                )
        }
    }

    private fun applyAuthoritativeRunReconciliation(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        reconciliation: AuthoritativeRunReconciliation,
    ) {
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return
            }
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val run = reconciliation.run
            val knownRuns =
                mergeRuns(
                    sessionRuns[sessionId].orEmpty(),
                    reconciliation.history.runs() + run,
                )
            sessionRuns[sessionId] = knownRuns
            if (!reconciliation.run.isActive()) {
                observationJobToCancel = runObservationJobs.remove(sessionId)
                observationToClose = runObservations.remove(sessionId)
            }
            if (reconciliation.decision == org.hermesnative.client.feature.entry.domain.RunReconciliationDecision.CONFIRMED) {
                sessionSendErrors.remove(sessionId)
                forgetObservationState(sessionId, reconciliation.run.id)
                val latestObservation = latestObservationState(sessionId, knownRuns)
                val latestRun = knownRuns.latestRun()
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        messages = reconciliation.history.messages.map { it.toSessionMessageUiState() }.chronological(),
                                        sendErrorCategory = null,
                                        latestRun = latestRun,
                                        activeRuns = knownRuns.activeRuns(),
                                        latestRunState =
                                            latestObservation?.state
                                                ?: reconciliation.run.takeIf { it.id == latestRun?.id }?.toRunPresentationState()
                                                ?: opened.latestRunState,
                                        activeResponse = latestObservation?.toSessionMessageUiState(),
                                        errorCategory = null,
                                        isStale = false,
                                    ),
                            ),
                    )
            } else {
                val previous = observationStateFor(sessionId, run.id)
                val uncertainState = uncertainObservationState(run, previous)
                rememberObservationState(uncertainState)
                val latestObservation = latestObservationState(sessionId, knownRuns)
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        latestRun = knownRuns.latestRun(),
                                        activeRuns = knownRuns.activeRuns(),
                                        latestRunState = latestObservation?.state ?: opened.latestRunState,
                                        activeResponse = latestObservation?.toSessionMessageUiState(),
                                        errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                        isStale = true,
                                    ),
                            ),
                    )
            }
        }
        observationToClose?.close()
        if (observationToClose != null) {
            observationJobToCancel?.cancel()
        }
    }

    private fun showRunReconciliationFailure(
        sessionId: SessionId,
        runId: RunId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ) {
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return
            }
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val knownRun =
                sessionRuns[sessionId].orEmpty().lastOrNull { it.id == runId }
                    ?: Run(runId, sessionId, UNCERTAIN_RUN_STATUS)
            val uncertainRun = knownRun
            val knownRuns = mergeRuns(sessionRuns[sessionId].orEmpty(), listOf(uncertainRun))
            val previous = observationStateFor(sessionId, runId)
            val uncertainState = uncertainObservationState(uncertainRun, previous)
            sessionRuns[sessionId] = knownRuns
            rememberObservationState(uncertainState)
            val latestObservation = latestObservationState(sessionId, knownRuns)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRun = knownRuns.latestRun(),
                                    activeRuns = knownRuns.activeRuns(),
                                    latestRunState = latestObservation?.state ?: opened.latestRunState,
                                    activeResponse = latestObservation?.toSessionMessageUiState(),
                                    errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                    isStale = true,
                                ),
                        ),
                )
        }
    }

    private fun uncertainObservationState(
        run: Run,
        previous: RunObservationState?,
    ): RunObservationState =
        (previous ?: RunObservationState(run = run, state = RunPresentationState.UNCERTAIN)).copy(
            run = run,
            state = RunPresentationState.UNCERTAIN,
            isStreaming = false,
        )

    private fun uncertainSendRun(sessionId: SessionId): Run {
        val run = Run(RunId("$UNCERTAIN_SEND_RUN_PREFIX${sessionId.value}"), sessionId, UNCERTAIN_RUN_STATUS)
        uncertainSendRunIds[sessionId] = run.id
        return run
    }

    private fun Run.isUncertainSendRun(): Boolean = uncertainSendRunIds[sessionId] == id

    private fun List<Run>.withoutUncertainSendRun(): List<Run> = filterNot { run -> run.isUncertainSendRun() }

    private fun List<Run>.latestActiveRun(): Run? = lastOrNull { it.isActive() && !it.isUncertainSendRun() }

    private fun markTimedOutSendUncertain(
        sessionId: SessionId,
        current: SessionListUiState,
        opened: OpenSessionUiState,
    ): Boolean {
        val uncertainRun = uncertainSendRun(sessionId)
        val knownRuns =
            mergeRuns(
                sessionRuns[sessionId].orEmpty(),
                listOf(uncertainRun),
            )
        val previous = observationStateFor(sessionId, uncertainRun.id)
        val uncertainState = uncertainObservationState(uncertainRun, previous)
        pendingRunDrafts.remove(sessionId)
        sessionRuns[sessionId] = knownRuns
        rememberObservationState(uncertainState)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isSending = false,
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState = RunPresentationState.UNCERTAIN,
                                activeResponse = uncertainState.toSessionMessageUiState(),
                                sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                isStale = true,
                            ),
                    ),
            )
        return true
    }

    private fun showOpenedSessionFailure(
        requestGeneration: Long,
        query: String,
    ) {
        updateCurrentSessionRequest(requestGeneration, query) { current ->
            current.openedSession?.let { openedSession ->
                current.copy(
                    openedSession =
                        openedSession.copy(
                            isRefreshing = false,
                            isStale = true,
                            errorCategory = SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED,
                        ),
                )
            } ?: current
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

    private fun rememberSessionRuns(
        sessionId: SessionId,
        openedSession: OpenedSession,
    ): List<Run> {
        val confirmedRuns = openedSession.history.runs()
        if (confirmedRuns.isNotEmpty()) {
            val confirmedRunIds = confirmedRuns.filterNot(Run::isActive).mapTo(mutableSetOf()) { it.id }
            val states = runObservationStates[sessionId]
            states?.keys?.removeAll(confirmedRunIds)
            if (states?.isEmpty() == true) {
                runObservationStates.remove(sessionId)
            }
            val localRuns = sessionRuns[sessionId].orEmpty()
            val unresolvedRuns =
                allObservationStates(sessionId)
                    .filter { state ->
                        state.state == RunPresentationState.UNCERTAIN &&
                            state.run.id !in confirmedRunIds &&
                            !state.run.isActive()
                    }
                    .map(RunObservationState::run)
            val retainedLocalRuns = localRuns.filter(Run::isActive) + unresolvedRuns
            sessionRuns[sessionId] = mergeRuns(confirmedRuns, retainedLocalRuns)
        }
        return sessionRuns[sessionId].orEmpty()
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

    private fun createRunJob(
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
                        if (runJobs[sessionId] === job) {
                            runJobs.remove(sessionId)
                        }
                    }
                }
            }
        synchronized(sessionRequestLock) {
            runJobs[sessionId] = job
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
        val refreshJob =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return@synchronized null
                if (current.sessions.none { it.id == sessionId }) return@synchronized null
                val mutation = current.sessionMutations[sessionId] ?: return@synchronized null
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
                sessionGateway?.let { gateway ->
                    createFirstPageLoadJob(
                        gateway = gateway,
                        query = current.searchQuery,
                        isRefreshing = true,
                        isSearching = false,
                        preserveSessions = true,
                    )
                }
            }
        refreshJob?.start()
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
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        val refreshJob =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return@synchronized null
                val shouldRefresh = current.nextCursor != null
                if (current.openedSession?.session?.id == sessionId) {
                    observationJobToCancel = runObservationJobs.remove(sessionId)
                    observationToClose = runObservations.remove(sessionId)
                    runObservationStates.remove(sessionId)
                }
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
                        createFirstPageLoadJob(
                            gateway = gateway,
                            query = current.searchQuery,
                            isRefreshing = true,
                            isSearching = false,
                            preserveSessions = true,
                        )
                    }
                } else {
                    null
                }
            }
        observationJobToCancel?.cancel()
        observationToClose?.close()
        refreshJob?.start()
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
                val openedSessionUiState = openedSession.toOpenSessionUiState()
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

    private fun createFirstPageLoadJob(
        gateway: SessionGatewayPort,
        query: String,
        isRefreshing: Boolean,
        isSearching: Boolean,
        preserveSessions: Boolean,
    ): Job? =
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return@synchronized null
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
                val requestConnectionGeneration = connectionGeneration
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
                        val applied =
                            updateCurrentSessionRequest(request.generation, request.query) { current ->
                                val authoritativeSession = openedSession.session.toSessionItemUiState()
                                val knownRuns = rememberSessionRuns(sessionId, openedSession)
                                val latestObservation = latestObservationState(sessionId, knownRuns)
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
                                        openedSession.toOpenSessionUiState(
                                            composerText = sessionDrafts[sessionId].orEmpty(),
                                            sendErrorCategory = sessionSendErrors[sessionId],
                                            latestRun = knownRuns.latestRun(),
                                            activeRuns = knownRuns.activeRuns(),
                                            isSending = runJobs.containsKey(sessionId),
                                            latestRunState = latestObservation?.state,
                                            activeResponse = latestObservation?.toSessionMessageUiState(),
                                        ),
                                    errorCategory = null,
                                )
                            }
                        if (applied) {
                            reconcileOpenedRun(
                                sessionId = sessionId,
                                requestSessionGeneration = request.generation,
                                requestConnectionGeneration = requestConnectionGeneration,
                                sessionGateway = gateway,
                                restartObservation = true,
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
        var observationToClose: RunEventObservation? = null
        var observationJobToCancel: Job? = null
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
            current.openedSession?.session?.id?.let { sessionId ->
                observationJobToCancel = runObservationJobs.remove(sessionId)
                observationToClose = runObservations.remove(sessionId)
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
        observationJobToCancel?.cancel()
        observationToClose?.close()
    }

    private fun updateComposerText(value: String) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession ?: return
            sessionDrafts[opened.session.id] = value
            sessionSendErrors.remove(opened.session.id)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    composerText = value,
                                    sendErrorCategory = null,
                                ),
                        ),
                )
        }
    }

    private fun sendMessage() {
        val gateway = runGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return@synchronized null
                val opened = current.openedSession ?: return@synchronized null
                val sessionId = opened.session.id
                val requestConnectionGeneration = connectionGeneration
                val requestSessionGeneration = sessionRequestGeneration
                val knownRuns =
                    sessionRuns[sessionId].orEmpty().ifEmpty {
                        (opened.activeRuns + listOfNotNull(opened.latestRun)).distinctBy { it.id }
                    }
                val latestRun = knownRuns.latestRun() ?: opened.latestRun
                val submissionState =
                    RunSubmissionState(
                        latestRun = latestRun,
                        activeRuns = knownRuns.activeRuns(),
                        isSubmissionPending = opened.isSending || runJobs.containsKey(sessionId),
                    )
                if (
                    !submissionState.canSubmit ||
                    opened.latestRunState == RunPresentationState.UNCERTAIN ||
                    opened.composerText.isBlank() ||
                    opened.isRefreshing ||
                    current.hasPendingMutation
                ) {
                    return@synchronized null
                }
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        isSending = true,
                                        sendErrorCategory = null,
                                    ),
                            ),
                    )
                sessionSendErrors.remove(sessionId)
                pendingRunDrafts[sessionId] = opened.composerText
                sessionDrafts[sessionId] = opened.composerText
                val knownRunIds = knownRuns.mapTo(mutableSetOf()) { it.id }
                createRunJob(sessionId) {
                    try {
                        val run =
                            withTimeout(sendTimeoutMillis) {
                                runInterruptible {
                                    SubmitMessage(gateway).execute(sessionId, opened.composerText)
                                }
                            }
                        if (applySubmittedRun(sessionId, run, requestConnectionGeneration)) {
                            onRunSubmissionCompleted?.invoke()
                        }
                    } catch (_: TimeoutCancellationException) {
                        if (reconcileTimedOutSend(
                                sessionId,
                                requestConnectionGeneration,
                                requestSessionGeneration,
                                knownRunIds,
                            )
                        ) {
                            synchronized(sessionRequestLock) {
                                sessionRuns[sessionId].orEmpty().latestActiveRun()
                                    ?.takeUnless { it.isUncertainSendRun() }
                            }?.let { activeRun -> startRunObservation(sessionId, activeRun) }
                            onRunSubmissionCompleted?.invoke()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: GatewayException) {
                        if (showMessageSendFailure(sessionId, requestConnectionGeneration)) {
                            onRunSubmissionCompleted?.invoke()
                        }
                    } catch (_: Exception) {
                        if (showMessageSendFailure(sessionId, requestConnectionGeneration)) {
                            onRunSubmissionCompleted?.invoke()
                        }
                    } finally {
                        onRunSubmissionSettled?.invoke()
                    }
                }
            }
        job?.start()
    }

    private suspend fun reconcileTimedOutSend(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        knownRunIds: Set<RunId>,
    ): Boolean {
        if (!beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) return false
        return try {
            val gateways =
                synchronized(sessionRequestLock) {
                    val sessionGateway = sessionGateway
                    val runGateway = runGateway
                    if (
                        connectionGeneration != requestConnectionGeneration ||
                        sessionRequestGeneration != requestSessionGeneration ||
                        sessionGateway == null ||
                        runGateway == null
                    ) {
                        null
                    } else {
                        sessionGateway to runGateway
                    }
                }
            if (gateways == null) {
                showTimedOutSendFailure(sessionId, requestConnectionGeneration, requestSessionGeneration)
                true
            } else {
                val result = ReconcileSession(gateways.first, gateways.second).execute(sessionId, knownRunIds)
                applyTimedOutSendReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration, result)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showTimedOutSendFailure(sessionId, requestConnectionGeneration, requestSessionGeneration)
            true
        } catch (_: Exception) {
            showTimedOutSendFailure(sessionId, requestConnectionGeneration, requestSessionGeneration)
            true
        } finally {
            finishReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)
        }
    }

    private fun applyTimedOutSendReconciliation(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        reconciliation: org.hermesnative.client.feature.entry.domain.SessionReconciliation,
    ): Boolean =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return@synchronized false
            }
            val current = _uiState.value.sessionList ?: return@synchronized false
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return@synchronized false
            val discoveredRun = reconciliation.discoveredRuns.singleOrNull()
            if (discoveredRun == null) {
                markTimedOutSendUncertain(sessionId, current, opened)
            } else {
                val decision = decideRunReconciliation(discoveredRun, reconciliation.history)
                val run = discoveredRun
                val knownRuns =
                    mergeRuns(
                        sessionRuns[sessionId].orEmpty().withoutUncertainSendRun(),
                        reconciliation.history.runs() + run,
                    )
                sessionRuns[sessionId] = knownRuns
                val uncertainSendRunId = uncertainSendRunIds.remove(sessionId)
                if (uncertainSendRunId != null) {
                    forgetObservationState(sessionId, uncertainSendRunId)
                }
                val submittedDraft = pendingRunDrafts.remove(sessionId)
                if (sessionDrafts[sessionId] == submittedDraft) {
                    sessionDrafts.remove(sessionId)
                }
                if (decision == org.hermesnative.client.feature.entry.domain.RunReconciliationDecision.CONFIRMED) {
                    sessionSendErrors.remove(sessionId)
                    forgetObservationState(sessionId, run.id)
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    openedSession =
                                        opened.copy(
                                            messages = reconciliation.history.messages.map { it.toSessionMessageUiState() }.chronological(),
                                            composerText = sessionDrafts[sessionId].orEmpty(),
                                            latestRun = knownRuns.latestRun(),
                                            activeRuns = knownRuns.activeRuns(),
                                            isSending = false,
                                            sendErrorCategory = null,
                                            latestRunState = run.toRunPresentationState(),
                                            activeResponse = null,
                                            errorCategory = null,
                                            isStale = false,
                                        ),
                                ),
                        )
                } else {
                    val previous = observationStateFor(sessionId, run.id)
                    val uncertainState = uncertainObservationState(run, previous)
                    rememberObservationState(uncertainState)
                    val latestObservation = latestObservationState(sessionId, knownRuns)
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    openedSession =
                                        opened.copy(
                                            composerText = sessionDrafts[sessionId].orEmpty(),
                                            latestRun = knownRuns.latestRun(),
                                            activeRuns = knownRuns.activeRuns(),
                                            isSending = false,
                                            sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                            latestRunState = latestObservation?.state ?: opened.latestRunState,
                                            activeResponse = latestObservation?.toSessionMessageUiState(),
                                            errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                            isStale = true,
                                        ),
                                ),
                        )
                }
                true
            }
        }

    private fun showTimedOutSendFailure(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ) {
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return
            }
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            markTimedOutSendUncertain(sessionId, current, opened)
        }
    }

    private fun applySubmittedRun(
        sessionId: SessionId,
        run: Run,
        requestConnectionGeneration: Long,
    ): Boolean {
        var shouldObserve = false
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        val applied =
            synchronized(sessionRequestLock) {
                if (connectionGeneration != requestConnectionGeneration) return@synchronized false
                sessionSendErrors.remove(sessionId)
                val submittedDraft = pendingRunDrafts.remove(sessionId)
                if (sessionDrafts[sessionId] == submittedDraft) {
                    sessionDrafts.remove(sessionId)
                }
                val knownRuns =
                    (sessionRuns[sessionId].orEmpty().filterNot { it.id == run.id } + run)
                        .also { sessionRuns[sessionId] = it }
                val current = _uiState.value.sessionList
                val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
                if (current != null && opened != null) {
                    observationJobToCancel = runObservationJobs.remove(sessionId)
                    observationToClose = runObservations.remove(sessionId)
                    val observationState =
                        observationStateFor(sessionId, run.id)
                            ?: RunEventStateTransition.initial(run)
                    rememberObservationState(observationState)
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    openedSession =
                                        opened.copy(
                                            composerText = sessionDrafts[sessionId].orEmpty(),
                                            latestRun = knownRuns.latestRun() ?: run,
                                            activeRuns = knownRuns.activeRuns(),
                                            isSending = false,
                                            sendErrorCategory = null,
                                            latestRunState = observationState.state,
                                            activeResponse = observationState.toSessionMessageUiState(),
                                        ),
                                ),
                        )
                    shouldObserve = run.isActive()
                }
                true
            }
        observationJobToCancel?.cancel()
        observationToClose?.close()
        if (applied && shouldObserve) {
            startRunObservation(sessionId, run)
        }
        return applied
    }

    private fun startRunObservation(
        sessionId: SessionId,
        run: Run,
    ) {
        var jobToStart: Job? = null
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList
            val opened = current?.openedSession
            if (
                opened == null ||
                opened.session.id != sessionId ||
                !run.isActive() ||
                runObservationJobs.containsKey(sessionId)
            ) {
                return
            }
            val state =
                observationStateFor(sessionId, run.id)
                    ?: RunEventStateTransition.initial(run)
            rememberObservationState(state)
            val knownRuns = sessionRuns[sessionId].orEmpty()
            val latestObservation = latestObservationState(sessionId, knownRuns)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRunState = latestObservation?.state ?: state.state,
                                    activeResponse = latestObservation?.toSessionMessageUiState() ?: state.toSessionMessageUiState(),
                                ),
                        ),
                )
            lateinit var observationJob: Job
            observationJob =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        observeRun(
                            sessionId = sessionId,
                            run = run,
                        )
                    } finally {
                        synchronized(sessionRequestLock) {
                            if (runObservationJobs[sessionId] === observationJob) {
                                runObservationJobs.remove(sessionId)
                            }
                        }
                    }
                }
            runObservationJobs[sessionId] = observationJob
            jobToStart = observationJob
        }
        jobToStart?.start()
    }

    private suspend fun observeRun(
        sessionId: SessionId,
        run: Run,
    ) {
        val observationJob = currentCoroutineContext()[Job] ?: return
        var observation: RunEventObservation? = null
        var shouldReconcile = false
        try {
            currentCoroutineContext().ensureActive()
            val gateway =
                synchronized(sessionRequestLock) {
                    runGateway?.takeIf {
                        _uiState.value.sessionList?.openedSession?.session?.id == sessionId
                    }
                } ?: return
            observation = ObserveRun(gateway).execute(run.id)
            synchronized(sessionRequestLock) {
                if (
                    _uiState.value.sessionList?.openedSession?.session?.id != sessionId ||
                    runObservationJobs[sessionId] !== observationJob
                ) {
                    return
                }
                runObservations[sessionId] = observation
            }
            var reachedTerminalState = false
            val activeObservation = observation ?: return
            for (event in activeObservation) {
                currentCoroutineContext().ensureActive()
                applyRunEvent(sessionId, event, observationJob)
                reachedTerminalState =
                    synchronized(sessionRequestLock) {
                        observationStateFor(sessionId, run.id)
                            ?.let { state -> state.state.isTerminal() || !state.run.isActive() } == true
                    }
                if (reachedTerminalState) break
            }
            if (!reachedTerminalState) {
                markRunObservationUncertain(sessionId, run.id, observationJob)
            }
            shouldReconcile = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (currentCoroutineContext()[Job]?.isActive == true) {
                markRunObservationUncertain(sessionId, run.id, observationJob)
                shouldReconcile = true
            }
        } finally {
            observation?.close()
            synchronized(sessionRequestLock) {
                if (runObservations[sessionId] === observation) {
                    runObservations.remove(sessionId)
                }
            }
        }
        val reconciliationRequest =
            if (shouldReconcile) {
                currentObservationReconciliationRequest(sessionId, run.id, observationJob)
            } else {
                null
            }
        if (reconciliationRequest != null) {
            reconcileRun(
                sessionId = sessionId,
                runId = run.id,
                requestConnectionGeneration = reconciliationRequest.connectionGeneration,
                requestSessionGeneration = reconciliationRequest.sessionGeneration,
                sessionGateway =
                    synchronized(sessionRequestLock) {
                        sessionGateway?.takeIf {
                            connectionGeneration == reconciliationRequest.connectionGeneration &&
                                sessionRequestGeneration == reconciliationRequest.sessionGeneration
                        }
                    } ?: return,
            )
        }
    }

    private fun currentObservationReconciliationRequest(
        sessionId: SessionId,
        runId: RunId,
        observationJob: Job,
    ): ReconciliationRequest? =
        synchronized(sessionRequestLock) {
            if (
                runObservationJobs[sessionId] !== observationJob ||
                _uiState.value.sessionList?.openedSession?.session?.id != sessionId ||
                observationStateFor(sessionId, runId) == null
            ) {
                null
            } else {
                ReconciliationRequest(connectionGeneration, sessionRequestGeneration)
            }
        }

    private fun shouldReconcileCurrentSession(
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        sessionId: SessionId,
    ): Boolean =
        synchronized(sessionRequestLock) {
            connectionGeneration == requestConnectionGeneration &&
                sessionRequestGeneration == requestSessionGeneration &&
                _uiState.value.sessionList?.openedSession?.session?.id == sessionId
        }

    private fun applyRunEvent(
        sessionId: SessionId,
        event: RunEvent,
        observationJob: Job,
    ) {
        synchronized(sessionRequestLock) {
            if (runObservationJobs[sessionId] !== observationJob) return
            val previous = observationStateFor(sessionId, event.runId) ?: return
            val next = RunEventStateTransition.apply(previous, event)
            rememberObservationState(next)
            val knownRuns =
                sessionRuns[sessionId].orEmpty()
                    .map { knownRun -> if (knownRun.id == event.runId) next.run else knownRun }
                    .ifEmpty { listOf(next.run) }
            sessionRuns[sessionId] = knownRuns
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val latestRun = knownRuns.latestRun() ?: next.run
            val latestObservation = latestObservationState(sessionId, knownRuns)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRun = latestRun,
                                    activeRuns = knownRuns.activeRuns(),
                                    latestRunState = latestObservation?.state ?: next.state,
                                    activeResponse = latestObservation?.toSessionMessageUiState() ?: next.toSessionMessageUiState(),
                                ),
                        ),
                )
        }
    }

    private fun markRunObservationUncertain(
        sessionId: SessionId,
        runId: RunId,
        observationJob: Job,
    ) {
        synchronized(sessionRequestLock) {
            if (runObservationJobs[sessionId] !== observationJob) return
            val previous = observationStateFor(sessionId, runId) ?: return
            if (previous.state.isTerminal() || !previous.run.isActive()) return
            val next = RunEventStateTransition.interrupted(previous)
            rememberObservationState(next)
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val knownRuns = sessionRuns[sessionId].orEmpty()
            val latestObservation = latestObservationState(sessionId, knownRuns)
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRunState = latestObservation?.state ?: RunPresentationState.UNCERTAIN,
                                    activeResponse = latestObservation?.toSessionMessageUiState() ?: next.toSessionMessageUiState(),
                                ),
                        ),
                )
        }
    }

    private fun observationStateFor(
        sessionId: SessionId,
        runId: RunId,
    ): RunObservationState? = runObservationStates[sessionId]?.get(runId)

    private fun rememberObservationState(state: RunObservationState) {
        runObservationStates.getOrPut(state.run.sessionId) { mutableMapOf() }[state.run.id] = state
    }

    private fun forgetObservationState(
        sessionId: SessionId,
        runId: RunId,
    ) {
        val states = runObservationStates[sessionId] ?: return
        states.remove(runId)
        if (states.isEmpty()) {
            runObservationStates.remove(sessionId)
        }
    }

    private fun latestObservationState(
        sessionId: SessionId,
        runs: List<Run>,
    ): RunObservationState? {
        val states = runObservationStates[sessionId] ?: return null
        return runs.asReversed().firstOrNull { states.containsKey(it.id) }?.let { states[it.id] } ?: states.values.lastOrNull()
    }

    private fun allObservationStates(sessionId: SessionId): List<RunObservationState> =
        runObservationStates[sessionId]?.values?.toList().orEmpty()

    private fun showMessageSendFailure(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
    ): Boolean {
        return synchronized(sessionRequestLock) {
            if (connectionGeneration != requestConnectionGeneration) return false
            pendingRunDrafts.remove(sessionId)
            sessionSendErrors[sessionId] = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            val current = _uiState.value.sessionList
            val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
            if (current != null && opened != null) {
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        isSending = false,
                                        sendErrorCategory = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED,
                                    ),
                            ),
                    )
            }
            true
        }
    }
}

private fun OpenedSession.toOpenSessionUiState(
    composerText: String = "",
    sendErrorCategory: MessageSendErrorCategory? = null,
    latestRun: Run? = null,
    activeRuns: List<Run> = history.runs().activeRuns(),
    isSending: Boolean = false,
    latestRunState: RunPresentationState? = null,
    activeResponse: SessionMessageUiState? = null,
    isRefreshing: Boolean = false,
): OpenSessionUiState =
    OpenSessionUiState(
        session = session.toSessionItemUiState(),
        messages = history.messages.map { it.toSessionMessageUiState() }.chronological(),
        composerText = composerText,
        sendErrorCategory = sendErrorCategory,
        latestRun = latestRun ?: history.latestRun(),
        activeRuns = activeRuns,
        isSending = isSending,
        latestRunState = latestRunState,
        activeResponse = activeResponse,
        isRefreshing = isRefreshing,
    )

private fun RunObservationState.toSessionMessageUiState(): SessionMessageUiState =
    SessionMessageUiState(
        id = "active-response:${run.id.value}",
        role = "assistant",
        content = responseText.takeIf(String::isNotEmpty),
        runId = run.id,
        runState = state,
        isStreaming = isStreaming,
        streamInterrupted = isStreamInterrupted,
    )

private fun SessionHistory.latestRun(): Run? = runs().latestRun()

private fun List<Run>.latestRun(): Run? = lastOrNull()

private fun List<Run>.activeRuns(): List<Run> = filter(Run::isActive)

private fun mergeRuns(
    existing: List<Run>,
    incoming: List<Run>,
): List<Run> =
    (existing + incoming)
        .associateBy { it.id }
        .values
        .toList()

private data class ReconciliationRequest(
    val connectionGeneration: Long,
    val sessionGeneration: Long,
)

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
