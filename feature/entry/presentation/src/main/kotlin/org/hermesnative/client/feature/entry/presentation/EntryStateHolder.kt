package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
import org.hermesnative.client.feature.entry.application.normalizeGatewayEndpoint
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
import org.hermesnative.client.feature.entry.domain.RunReconciliationDecision
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunSubmissionState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionReconciliation
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.isValid
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
private const val RECOVERY_PENDING_STATUS = "recovery_pending"

data class PendingRunSubmissionKey(
    val endpoint: String,
    val sessionId: SessionId,
)

interface RunSubmissionUncertaintyStore {
    fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId> = emptySet(),
    )

    fun remove(key: PendingRunSubmissionKey)

    fun contains(key: PendingRunSubmissionKey): Boolean

    fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> = emptySet()

    fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
    ) = Unit

    fun boundRunId(key: PendingRunSubmissionKey): RunId? = null

    fun markSettled(key: PendingRunSubmissionKey) = Unit

    fun isSettled(key: PendingRunSubmissionKey): Boolean = false

    fun markAmbiguous(key: PendingRunSubmissionKey) = Unit

    fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean = false

    fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
    ): Boolean {
        return false
    }
}

object ProcessRunSubmissionUncertaintyStore : RunSubmissionUncertaintyStore {
    private val lock = Any()
    private val keys = mutableMapOf<PendingRunSubmissionKey, UncertaintyRecord>()

    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
    ) {
        synchronized(lock) {
            keys.getOrPut(key) { UncertaintyRecord() }.knownRunIds += knownRunIds
        }
    }

    override fun remove(key: PendingRunSubmissionKey) {
        synchronized(lock) { keys.remove(key) }
    }

    override fun contains(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { key in keys }

    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> {
        return synchronized(lock) { keys[key]?.knownRunIds?.toSet().orEmpty() }
    }

    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
    ) {
        synchronized(lock) {
            keys.getOrPut(key) { UncertaintyRecord() }.boundRunId = runId
        }
    }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? = synchronized(lock) { keys[key]?.boundRunId }

    override fun markSettled(key: PendingRunSubmissionKey) {
        synchronized(lock) {
            keys[key]?.settled = true
        }
    }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { keys[key]?.settled == true }

    override fun markAmbiguous(key: PendingRunSubmissionKey) {
        synchronized(lock) {
            keys[key]?.requiresRunMatch = true
        }
    }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean {
        return synchronized(lock) { keys[key]?.requiresRunMatch == true }
    }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
    ): Boolean =
        synchronized(lock) {
            if (keys[key]?.knownRunIds?.toSet() != knownRunIds) {
                false
            } else {
                keys.remove(key)
                true
            }
        }
}

object NoOpRunSubmissionUncertaintyStore : RunSubmissionUncertaintyStore {
    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
    ) = Unit

    override fun remove(key: PendingRunSubmissionKey) = Unit

    override fun contains(key: PendingRunSubmissionKey): Boolean = false
}

private data class UncertaintyRecord(
    val knownRunIds: MutableSet<RunId> = mutableSetOf(),
    var boundRunId: RunId? = null,
    var settled: Boolean = false,
    var requiresRunMatch: Boolean = false,
)

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
    private val runRecoveryRegistry: RunRecoveryRegistry? = null,
    private val updateRunRecoveryEndpoint: ((String?) -> Unit)? = null,
    private val persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    private val removeRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    private val removeGatewayConnectionUseCase: RemoveGatewayConnection? = null,
    private val runSubmissionUncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
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
    private var recoveryJob: Job? = null
    private var sessionGateway: SessionGatewayPort? = null
    private var runGateway: RunGatewayPort? = null
    private val sessionRequestLock = Any()
    private val recoveryPersistenceLock = Any()
    private val connectionPersistenceLock = Any()
    private var sessionRequestGeneration = 0L
    private var connectionGeneration = 0L
    private val mutationJobs = mutableMapOf<SessionId, Job>()
    private val runJobs = mutableMapOf<SessionId, Job>()
    private val sessionDrafts = mutableMapOf<SessionId, String>()
    private val sessionSendErrors = mutableMapOf<SessionId, MessageSendErrorCategory>()
    private val pendingRunDrafts = mutableMapOf<SessionId, String>()
    private val pendingDisconnectedRecoveryEntries = mutableMapOf<String, MutableSet<RunRecoveryEntry>>()
    private val connectionRecoveryJobs = mutableSetOf<Job>()
    private val pendingCreateSessions = mutableMapOf<RecoverySessionKey, Set<RunId>>()
    private val pendingCreateStarted = mutableSetOf<RecoverySessionKey>()

    /** Runs returned by a local createRun response; history-only Runs never enter this map. */
    private val sessionRuns = mutableMapOf<SessionId, List<Run>>()
    private val unresolvedLocalRunIds = mutableMapOf<RecoverySessionKey, MutableSet<RunId>>()
    private val authoritativeSessionHistoryGenerations = mutableMapOf<SessionId, Long>()
    private val authoritativeSessionRuns = mutableMapOf<SessionId, List<Run>>()
    private val runObservationJobs = mutableMapOf<SessionId, Job>()
    private val runObservations = mutableMapOf<SessionId, RunEventObservation>()
    private val runObservationRunIds = mutableMapOf<SessionId, RunId>()
    private val pendingRunObservationRequests = mutableMapOf<SessionId, ObserverStartRequest>()
    private val runObservationStates = mutableMapOf<SessionId, MutableMap<RunId, RunObservationState>>()
    private val unresolvedSubmissionSessions = mutableSetOf<SessionId>()
    private val ambiguousSubmissionSessions = mutableSetOf<SessionId>()
    private val recoveryUnavailableSessions = mutableSetOf<SessionId>()
    private val uncertainSendDrafts = mutableMapOf<SessionId, String>()
    private val uncertainSubmissionRunIds = mutableMapOf<SessionId, RunId>()
    private val pendingTimedOutSends = mutableMapOf<SessionId, TimedOutSendRecovery>()
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
            EntryUiEvent.SendMessageClicked -> retryUncertainSubmissionOrSend()
            EntryUiEvent.RemoveGatewayConnectionClicked -> removeGatewayConnection()
        }
    }

    fun close() {
        var observationsToClose: List<RunEventObservation> = emptyList()
        val jobsToCancel =
            synchronized(connectionPersistenceLock) {
                synchronized(sessionRequestLock) {
                    sessionRequestGeneration += 1
                    connectionGeneration += 1
                    sessionGateway = null
                    runGateway = null
                    val verificationJobToCancel = verificationJob
                    val sessionJobToCancel = sessionJob
                    val recoveryJobToCancel = recoveryJob
                    verificationJob = null
                    sessionJob = null
                    recoveryJob = null
                    val requestJobs =
                        (mutationJobs.values + runJobs.values + runObservationJobs.values + connectionRecoveryJobs).toList()
                    observationsToClose = runObservations.values.toList()
                    mutationJobs.clear()
                    runJobs.clear()
                    runObservationJobs.clear()
                    runObservations.clear()
                    runObservationRunIds.clear()
                    pendingRunObservationRequests.clear()
                    runObservationStates.clear()
                    unresolvedSubmissionSessions.clear()
                    ambiguousSubmissionSessions.clear()
                    recoveryUnavailableSessions.clear()
                    uncertainSendDrafts.clear()
                    uncertainSubmissionRunIds.clear()
                    pendingCreateSessions.forEach { (key, knownRunIds) ->
                        val submissionKey = PendingRunSubmissionKey(key.endpoint, key.sessionId)
                        runSubmissionUncertaintyStore.add(submissionKey, knownRunIds)
                        if (key !in pendingCreateStarted) {
                            runSubmissionUncertaintyStore.markSettled(submissionKey)
                        }
                    }
                    pendingCreateSessions.clear()
                    pendingCreateStarted.clear()
                    unresolvedLocalRunIds.clear()
                    pendingTimedOutSends.clear()
                    reconcilingSessions.clear()
                    connectionRecoveryJobs.clear()
                    sessionDrafts.clear()
                    sessionSendErrors.clear()
                    pendingRunDrafts.clear()
                    sessionRuns.clear()
                    authoritativeSessionHistoryGenerations.clear()
                    authoritativeSessionRuns.clear()
                    requestJobs + listOfNotNull(verificationJobToCancel, sessionJobToCancel, recoveryJobToCancel)
                }
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
        val job =
            synchronized(connectionPersistenceLock) {
                synchronized(sessionRequestLock) {
                    val state = _uiState.value
                    if (!state.connectionSetupRequested || state.isVerifying || state.isConnected) {
                        null
                    } else {
                        verificationJob?.cancel()
                        val requestConnectionGeneration = connectionGeneration
                        _uiState.value = state.copy(isVerifying = true, errorCategory = null)
                        lateinit var verificationJobToStart: Job
                        verificationJobToStart =
                            scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    verifier.executeWithoutPersistence(
                                        endpoint = state.endpoint,
                                        bearerCredential = state.bearerCredential,
                                    )
                                    val normalizedEndpoint = normalizeGatewayEndpoint(state.endpoint)
                                    val canPersist =
                                        synchronized(sessionRequestLock) {
                                            connectionGeneration == requestConnectionGeneration
                                        }
                                    if (!canPersist) return@launch
                                    synchronized(connectionPersistenceLock) {
                                        val stillCurrent =
                                            synchronized(sessionRequestLock) {
                                                connectionGeneration == requestConnectionGeneration
                                            }
                                        if (!stillCurrent) return@launch
                                        verifier.persist(normalizedEndpoint)
                                    }
                                    val gateway =
                                        synchronized(sessionRequestLock) {
                                            if (connectionGeneration != requestConnectionGeneration) {
                                                null
                                            } else {
                                                val gateway =
                                                    sessionGatewayFactory?.invoke(
                                                        normalizedEndpoint,
                                                        state.bearerCredential,
                                                    )
                                                updateRunRecoveryEndpoint?.invoke(normalizedEndpoint)
                                                sessionGateway = gateway
                                                runGateway =
                                                    runGatewayFactory?.invoke(
                                                        normalizedEndpoint,
                                                        state.bearerCredential,
                                                    ) ?: (gateway as? RunGatewayPort)
                                                _uiState.value =
                                                    _uiState.value.copy(
                                                        endpoint = normalizedEndpoint,
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
                                    flushPendingRecoveryEntries(normalizedEndpoint)
                                    startRunRecovery(requestConnectionGeneration)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: GatewayException) {
                                    showFailure(error.category.toUserFacingCategory(), requestConnectionGeneration)
                                } catch (_: Exception) {
                                    showFailure(EntryErrorCategory.GATEWAY_REQUEST_FAILED, requestConnectionGeneration)
                                }
                            }
                        verificationJob = verificationJobToStart
                        verificationJobToStart
                    }
                }
            } ?: return
        job.start()
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
        var observationsToClose: List<RunEventObservation> = emptyList()
        val jobsToCancel =
            synchronized(connectionPersistenceLock) {
                val jobsToCancelInside =
                    synchronized(sessionRequestLock) {
                        sessionRequestGeneration += 1
                        connectionGeneration += 1
                        val verificationJobToCancel = verificationJob
                        val sessionJobToCancel = sessionJob
                        verificationJob = null
                        sessionJob = null
                        pendingCreateSessions.forEach { (key, knownRunIds) ->
                            val submissionKey = PendingRunSubmissionKey(key.endpoint, key.sessionId)
                            runSubmissionUncertaintyStore.add(submissionKey, knownRunIds)
                            if (key !in pendingCreateStarted) {
                                runSubmissionUncertaintyStore.markSettled(submissionKey)
                            }
                        }
                        pendingCreateStarted.clear()
                        pendingCreateSessions.clear()
                        sessionGateway = null
                        runGateway = null
                        val recoveryJobToCancel = recoveryJob
                        recoveryJob = null
                        val connectionRecoveryJobsToCancel = connectionRecoveryJobs.toList()
                        connectionRecoveryJobs.clear()
                        sessionDrafts.clear()
                        sessionSendErrors.clear()
                        pendingRunDrafts.clear()
                        sessionRuns.clear()
                        authoritativeSessionHistoryGenerations.clear()
                        authoritativeSessionRuns.clear()
                        observationsToClose = runObservations.values.toList()
                        (mutationJobs.values + runJobs.values + runObservationJobs.values).toList().also {
                            mutationJobs.clear()
                            runJobs.clear()
                            runObservationJobs.clear()
                            runObservations.clear()
                            runObservationRunIds.clear()
                            pendingRunObservationRequests.clear()
                            runObservationStates.clear()
                            unresolvedSubmissionSessions.clear()
                            ambiguousSubmissionSessions.clear()
                            recoveryUnavailableSessions.clear()
                            uncertainSendDrafts.clear()
                            uncertainSubmissionRunIds.clear()
                            pendingTimedOutSends.clear()
                            reconcilingSessions.clear()
                        }
                            .plus(listOfNotNull(verificationJobToCancel, sessionJobToCancel, recoveryJobToCancel))
                            .plus(connectionRecoveryJobsToCancel)
                    }.also {
                        removeGatewayConnectionUseCase?.execute()
                        updateRunRecoveryEndpoint?.invoke(null)
                    }
                _uiState.value = EntryState().toUiState()
                jobsToCancelInside
            }
        jobsToCancel.forEach(Job::cancel)
        observationsToClose.forEach(RunEventObservation::close)
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

    private fun flushPendingRecoveryEntries(endpoint: String) {
        val entries =
            synchronized(sessionRequestLock) {
                pendingDisconnectedRecoveryEntries.remove(endpoint).orEmpty().toList()
            }
        entries.forEach { entry -> persistOrQueueRecoveryEntry(endpoint, entry) }
    }

    private fun persistOrQueueRecoveryEntry(
        endpoint: String,
        entry: RunRecoveryEntry,
    ): Boolean {
        val persistence =
            synchronized(sessionRequestLock) {
                persistRunRecoveryEntry to runRecoveryRegistry
            }
        if (persistence.first == null && persistence.second == null) return true
        return try {
            synchronized(recoveryPersistenceLock) {
                persistence.first?.invoke(endpoint, entry)
                    ?: persistence.second?.save(entry)
                    ?: throw IllegalStateException("No endpoint-scoped recovery writer is configured.")
            }
            true
        } catch (_: Exception) {
            synchronized(sessionRequestLock) {
                pendingDisconnectedRecoveryEntries
                    .getOrPut(endpoint, ::mutableSetOf)
                    .add(entry)
            }
            false
        }
    }

    private fun removeRecoveryEntry(
        endpoint: String?,
        entry: RunRecoveryEntry,
    ): Boolean {
        return try {
            synchronized(recoveryPersistenceLock) {
                if (endpoint != null) {
                    removeRunRecoveryEntry?.invoke(endpoint, entry) ?: runRecoveryRegistry?.remove(entry)
                } else {
                    runRecoveryRegistry?.remove(entry)
                }
            }
            synchronized(sessionRequestLock) {
                forgetPendingRecoveryEntry(endpoint, entry)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startRunRecovery(expectedConnectionGeneration: Long) {
        val registry = runRecoveryRegistry ?: return
        val recoveryContext =
            synchronized(sessionRequestLock) {
                if (connectionGeneration != expectedConnectionGeneration) {
                    null
                } else {
                    val sessionGateway = sessionGateway
                    val runGateway = runGateway
                    if (sessionGateway == null || runGateway == null) {
                        null
                    } else {
                        sessionGateway to runGateway
                    }
                }
            } ?: return
        val sessionGateway = recoveryContext.first
        val runGateway = recoveryContext.second
        val shouldLoad =
            synchronized(sessionRequestLock) {
                connectionGeneration == expectedConnectionGeneration
            }
        if (!shouldLoad) return
        val entries =
            try {
                registry.load().filter(RunRecoveryEntry::isValid)
            } catch (_: Exception) {
                return
            }

        if (entries.isEmpty()) return

        lateinit var job: Job
        var jobToCancel: Job? = null
        synchronized(sessionRequestLock) {
            if (connectionGeneration != expectedConnectionGeneration) {
                return
            }
            entries.forEach { entry ->
                rememberRecoveredRun(
                    entry = entry,
                    run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                    updateVisibleState = false,
                )
            }
            jobToCancel = recoveryJob
            job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        entries.forEach { entry ->
                            currentCoroutineContext().ensureActive()
                            val expectedHistoryGeneration =
                                synchronized(sessionRequestLock) {
                                    authoritativeSessionHistoryGenerations[entry.sessionId] ?: 0L
                                }
                            recoverRun(
                                entry = entry,
                                sessionGateway = sessionGateway,
                                runGateway = runGateway,
                                expectedConnectionGeneration = expectedConnectionGeneration,
                                expectedHistoryGeneration = expectedHistoryGeneration,
                            )
                        }
                    } finally {
                        synchronized(sessionRequestLock) {
                            if (recoveryJob === job) recoveryJob = null
                        }
                    }
                }
            recoveryJob = job
        }
        jobToCancel?.cancel()
        job.start()
    }

    private suspend fun recoverRun(
        entry: RunRecoveryEntry,
        sessionGateway: SessionGatewayPort,
        runGateway: RunGatewayPort,
        expectedConnectionGeneration: Long,
        expectedHistoryGeneration: Long,
        requestSessionGeneration: Long? = null,
        updateVisibleUi: Boolean = false,
    ) {
        val reconciliation =
            try {
                runInterruptible { ReconcileRun(runGateway, sessionGateway).execute(entry.runId, entry.sessionId) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }
        applyAuthoritativeRunReconciliation(
            sessionId = entry.sessionId,
            requestConnectionGeneration = expectedConnectionGeneration,
            requestSessionGeneration = requestSessionGeneration,
            requestHistoryGeneration = expectedHistoryGeneration,
            reconciliation = reconciliation,
            updateVisibleUi = updateVisibleUi,
        )
    }

    private fun rememberRecoveredRun(
        entry: RunRecoveryEntry,
        run: Run,
        updateVisibleState: Boolean = true,
    ) {
        val pendingKey = PendingRunSubmissionKey(_uiState.value.endpoint, entry.sessionId)
        if (
            runSubmissionUncertaintyStore.contains(pendingKey) &&
            entry.runId !in runSubmissionUncertaintyStore.knownRunIds(pendingKey)
        ) {
            val boundRunId = uncertainSubmissionRunIds[entry.sessionId]
            if (boundRunId == null || boundRunId == entry.runId) {
                uncertainSubmissionRunIds[entry.sessionId] = entry.runId
            } else {
                uncertainSubmissionRunIds.remove(entry.sessionId)
                unresolvedSubmissionSessions += entry.sessionId
                ambiguousSubmissionSessions += entry.sessionId
                runSubmissionUncertaintyStore.markAmbiguous(pendingKey)
            }
        }
        sessionRuns[entry.sessionId] =
            mergeRuns(sessionRuns[entry.sessionId].orEmpty(), listOf(run))
        val previous = observationStateFor(entry.sessionId, entry.runId)
        val state =
            if (run.isActive()) {
                val initial = RunEventStateTransition.initial(run)
                previous?.let {
                    initial.copy(
                        responseText = it.responseText,
                        processedEventIds = it.processedEventIds,
                    )
                } ?: initial
            } else {
                uncertainObservationState(run, previous)
            }
        rememberObservationState(state)
        if (updateVisibleState) updateVisibleRunState(entry.sessionId)
    }

    private fun updateVisibleRunState(sessionId: SessionId) {
        val current = _uiState.value.sessionList ?: return
        val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
        val knownRuns = visibleSessionRuns(sessionId)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState = latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                activeResponse = latestObservation?.toSessionMessageUiState(),
                            ),
                    ),
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
                visibleSessionRuns(openedSession.session.id)
                    .latestActiveRun()
                    ?.id
                    ?: latestObservedObservationState(openedSession.session.id, visibleSessionRuns(openedSession.session.id))?.run?.id
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
                        val latestRun = knownRuns.latestRun()
                        current.copy(
                            sessions =
                                current.sessions
                                    .map { item -> if (item.id == sessionId) authoritativeSession else item }
                                    .orderedSessions(),
                            openedSession =
                                openedSession.toOpenSessionUiState(
                                    composerText = sessionDrafts[sessionId] ?: previous.composerText,
                                    sendErrorCategory = sendErrorCategoryFor(sessionId),
                                    hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                    latestRun = latestRun,
                                    activeRuns = knownRuns.activeRuns(),
                                    isSending = previous.isSending || runJobs.containsKey(sessionId),
                                    latestRunState = latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                    activeResponse = latestObservation?.toSessionMessageUiState(),
                                    isRefreshing = previous.isRefreshing,
                                ).copy(
                                    isReconciliationInProgress =
                                        previous.isReconciliationInProgress ||
                                            openedSession.history.latestRun() != null,
                                ),
                            errorCategory = null,
                        )
                    }
                }
            if (applied) {
                val pendingTimeoutRecovery =
                    synchronized(sessionRequestLock) {
                        pendingTimedOutSends[sessionId]
                    }
                val pendingSettledRecovery =
                    if (pendingTimeoutRecovery == null) {
                        prepareSettledSubmissionRecovery(sessionId)
                    } else {
                        null
                    }
                if (pendingTimeoutRecovery != null) {
                    reconcileTimedOutSendNow(
                        sessionId = sessionId,
                        requestConnectionGeneration = requestConnectionGeneration,
                        requestSessionGeneration = request.generation,
                        knownRunIds = pendingTimeoutRecovery.knownRunIds,
                    )
                } else if (pendingSettledRecovery != null) {
                    reconcileTimedOutSendNow(
                        sessionId = sessionId,
                        requestConnectionGeneration = requestConnectionGeneration,
                        requestSessionGeneration = request.generation,
                        knownRunIds = pendingSettledRecovery.knownRunIds,
                        resolveWhenNoNewRun = true,
                    )
                } else {
                    reconcileOpenedRun(
                        sessionId = sessionId,
                        requestSessionGeneration = request.generation,
                        requestConnectionGeneration = requestConnectionGeneration,
                        sessionGateway = gateway,
                        restartObservation = true,
                        runIdToReconcile =
                            runIdToReconcile
                                ?: historyRunIdToReconcile(sessionId, openedSession),
                        clearRefreshWhenNoRun = true,
                    )
                }
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
        val recoveryEntries =
            try {
                runRecoveryRegistry?.load().orEmpty()
            } catch (_: Exception) {
                null
            }
        val recoveryLoadFailed = recoveryEntries == null
        val sessionRecoveryEntries = recoveryEntries.orEmpty().filter { entry -> entry.sessionId == sessionId }
        val runIds =
            synchronized(sessionRequestLock) {
                val ids = linkedSetOf<RunId>()
                runIdToReconcile?.let(ids::add)
                runSubmissionUncertaintyStore
                    .boundRunId(PendingRunSubmissionKey(_uiState.value.endpoint, sessionId))
                    ?.let { boundRunId ->
                        uncertainSubmissionRunIds[sessionId] = boundRunId
                    }
                if (recoveryLoadFailed) {
                    recoveryUnavailableSessions.add(sessionId)
                } else {
                    recoveryUnavailableSessions.remove(sessionId)
                }
                sessionRecoveryEntries.forEach { entry ->
                    ids += entry.runId
                    if (
                        sessionRuns[sessionId].orEmpty().none { it.id == entry.runId } &&
                        observationStateFor(sessionId, entry.runId) == null
                    ) {
                        rememberRecoveredRun(
                            entry = entry,
                            run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                            updateVisibleState = false,
                        )
                    }
                }
                sessionRuns[sessionId].orEmpty().filter(Run::isActive).mapTo(ids) { it.id }
                unresolvedLocalRunIds[recoverySessionKey(sessionId)].orEmpty().forEach(ids::add)
                uncertainSubmissionRunIds[sessionId]?.let(ids::add)
                visibleSessionRuns(sessionId).latestActiveRun()?.id?.let(ids::add)
                if (ids.isEmpty()) {
                    latestObservedObservationState(sessionId, visibleSessionRuns(sessionId))
                        ?.takeIf { state -> !state.state.isTerminal() }
                        ?.run
                        ?.id
                        ?.let(ids::add)
                }
                ids.toList()
            }
        if (runIds.isEmpty()) {
            if (clearRefreshWhenNoRun) {
                finishReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)
            }
            if (recoveryLoadFailed) {
                markRecoveryUnavailable(sessionId, requestConnectionGeneration, requestSessionGeneration)
            }
            return
        }

        var activeRunToObserve: Run? = null
        runIds.forEach { runId ->
            val run =
                synchronized(sessionRequestLock) {
                    visibleSessionRuns(sessionId).lastOrNull { it.id == runId }
                        ?: observationStateFor(sessionId, runId)?.run
                        ?: unresolvedLocalRunIds[recoverySessionKey(sessionId)]
                            ?.takeIf { runId in it }
                            ?.let { Run(runId, sessionId, UNCERTAIN_RUN_STATUS) }
                        ?: uncertainSubmissionRunIds[sessionId]
                            ?.takeIf { runId == it }
                            ?.let { Run(runId, sessionId, UNCERTAIN_RUN_STATUS) }
                } ?: return@forEach
            val reconciliation =
                reconcileRun(
                    sessionId = sessionId,
                    runId = run.id,
                    requestConnectionGeneration = requestConnectionGeneration,
                    requestSessionGeneration = requestSessionGeneration,
                    sessionGateway = sessionGateway,
                )
            val reconciledRun = reconciliation?.run
            if (reconciledRun?.isActive() == true || (reconciledRun == null && activeRunToObserve == null && run.isActive())) {
                activeRunToObserve = reconciledRun ?: run
            }
        }
        val runToObserve = activeRunToObserve
        if (
            restartObservation &&
            runToObserve != null &&
            shouldReconcileCurrentSession(requestConnectionGeneration, requestSessionGeneration, sessionId)
        ) {
            startRunObservation(
                sessionId = sessionId,
                run = runToObserve,
                expectedConnectionGeneration = requestConnectionGeneration,
                expectedSessionGeneration = requestSessionGeneration,
            )
        }
        if (recoveryLoadFailed) {
            markRecoveryUnavailable(sessionId, requestConnectionGeneration, requestSessionGeneration)
        }
    }

    private fun markRecoveryUnavailable(
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
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    isRefreshing = false,
                                    isStale = true,
                                    errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                    sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                    hasUnresolvedSubmission = true,
                                    latestRunState = opened.latestRunState ?: RunPresentationState.UNCERTAIN,
                                    isReconciliationInProgress = false,
                                ),
                        ),
                )
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
        requestSessionGeneration: Long?,
        reconciliation: AuthoritativeRunReconciliation,
        requestHistoryGeneration: Long? = null,
        updateVisibleUi: Boolean = true,
    ) {
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        var recoveryEntryToRemove: RunRecoveryEntry? = null
        var recoveryEndpointToRemove: String? = null
        var shouldRemoveRecoveryEntry = false
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                (updateVisibleUi && sessionRequestGeneration != requestSessionGeneration)
            ) {
                return
            }
            val shouldUpdateVisibleUi =
                updateVisibleUi &&
                    requestSessionGeneration != null &&
                    sessionRequestGeneration == requestSessionGeneration
            val current = _uiState.value.sessionList
            val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
            val run = reconciliation.run
            val decision = reconciliation.decision
            val authoritativeRuns = reconciliation.history.runs()
            val currentlyObservedRunId = runObservationRunIds[sessionId]
            val terminalRunIds =
                authoritativeRuns
                    .filterNot(Run::isActive)
                    .filter { it.id != currentlyObservedRunId || it.id == run.id }
                    .mapTo(mutableSetOf()) { it.id }
            if (decision != RunReconciliationDecision.CONFIRMED) {
                terminalRunIds.remove(run.id)
            }
            val shouldApplyReconciliationState =
                requestHistoryGeneration == null ||
                    (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) == requestHistoryGeneration

            if (shouldApplyReconciliationState) {
                forgetConfirmedObservationStates(sessionId, terminalRunIds)
                val incomingAuthoritativeRuns = authoritativeRuns + run
                val incomingAuthoritativeRunIds = incomingAuthoritativeRuns.mapTo(mutableSetOf()) { it.id }
                val retainedAuthoritativeRuns =
                    authoritativeSessionRuns[sessionId]
                        .orEmpty()
                        .filter { existing -> existing.id !in incomingAuthoritativeRunIds }
                authoritativeSessionRuns[sessionId] =
                    mergeRuns(retainedAuthoritativeRuns, incomingAuthoritativeRuns)
                if (isLocallyOwnedRun(sessionId, run.id)) {
                    sessionRuns[sessionId] = mergeRuns(sessionRuns[sessionId].orEmpty(), listOf(run))
                }
                val knownRuns = visibleSessionRuns(sessionId)
                if (!reconciliation.run.isActive()) {
                    if (runObservationRunIds[sessionId] == run.id) {
                        observationJobToCancel = runObservationJobs[sessionId]
                        observationToClose = runObservations.remove(sessionId)
                        runObservationRunIds.remove(sessionId)
                    }
                }
                val isBoundSubmission =
                    uncertainSubmissionRunIds[sessionId] == run.id ||
                        unresolvedLocalRunIds[recoverySessionKey(sessionId)]?.contains(run.id) == true
                val canClearSendState = isBoundSubmission
                if (!run.isActive()) {
                    forgetUnresolvedLocalRun(sessionId, run.id)
                }
                if (decision == RunReconciliationDecision.CONFIRMED) {
                    if (!run.isActive()) {
                        recoveryEntryToRemove = RunRecoveryEntry(sessionId = sessionId, runId = run.id)
                        recoveryEndpointToRemove = _uiState.value.endpoint.takeIf(String::isNotBlank)
                    }
                    if (isBoundSubmission) {
                        uncertainSubmissionRunIds.remove(sessionId)
                        if (!run.isActive()) {
                            runSubmissionUncertaintyStore.remove(pendingRunSubmissionKey(sessionId))
                        }
                    }
                    if (canClearSendState) {
                        sessionSendErrors.remove(sessionId)
                        val uncertainDraft = uncertainSendDrafts.remove(sessionId)
                        if (uncertainDraft != null && sessionDrafts[sessionId] == uncertainDraft) {
                            sessionDrafts.remove(sessionId)
                        }
                    }
                    ambiguousSubmissionSessions.remove(sessionId)
                    forgetObservationState(sessionId, reconciliation.run.id)
                    if (!run.isActive()) {
                        val recoveryKey = recoverySessionKey(sessionId)
                        unresolvedLocalRunIds[recoveryKey]?.remove(run.id)
                        if (unresolvedLocalRunIds[recoveryKey].isNullOrEmpty()) {
                            unresolvedLocalRunIds.remove(recoveryKey)
                        }
                    }
                    val latestObservation = latestObservationState(sessionId, knownRuns)
                    if (shouldUpdateVisibleUi && current != null && opened != null) {
                        val latestRun = knownRuns.latestRun()
                        _uiState.value =
                            _uiState.value.copy(
                                sessionList =
                                    current.copy(
                                        openedSession =
                                            opened.copy(
                                                messages =
                                                    reconciliation.history.messages
                                                        .map { it.toSessionMessageUiState() }
                                                        .chronological(),
                                                composerText = sessionDrafts[sessionId].orEmpty(),
                                                sendErrorCategory = if (canClearSendState) null else opened.sendErrorCategory,
                                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                                latestRun = latestRun,
                                                activeRuns = knownRuns.activeRuns(),
                                                latestRunState = latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                                activeResponse = latestObservation?.toSessionMessageUiState(),
                                                errorCategory = null,
                                                isStale = false,
                                            ),
                                    ),
                            )
                    }
                } else {
                    val previous = observationStateFor(sessionId, run.id)
                    val uncertainState = uncertainObservationState(run, previous)
                    rememberObservationState(uncertainState)
                    if (shouldUpdateVisibleUi && current != null && opened != null) {
                        val latestRun = visibleSessionRuns(sessionId).latestRun()
                        val latestObservation = latestObservationState(sessionId, visibleSessionRuns(sessionId))
                        _uiState.value =
                            _uiState.value.copy(
                                sessionList =
                                    current.copy(
                                        openedSession =
                                            opened.copy(
                                                latestRun = latestRun,
                                                activeRuns = visibleSessionRuns(sessionId).activeRuns(),
                                                sendErrorCategory = opened.sendErrorCategory,
                                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                                latestRunState = latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                                activeResponse = latestObservation?.toSessionMessageUiState(),
                                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                                isStale = true,
                                            ),
                                    ),
                            )
                    }
                }
            } else if (
                decision == RunReconciliationDecision.CONFIRMED &&
                !run.isActive() &&
                authoritativeSessionRuns[sessionId].orEmpty().any { knownRun ->
                    knownRun.id == run.id && !knownRun.isActive()
                }
            ) {
                recoveryEntryToRemove = RunRecoveryEntry(sessionId = sessionId, runId = run.id)
                recoveryEndpointToRemove = _uiState.value.endpoint.takeIf(String::isNotBlank)
            }
        }
        observationToClose?.close()
        observationJobToCancel?.cancel()
        recoveryEntryToRemove?.let { entry ->
            synchronized(sessionRequestLock) {
                val endpoint = recoveryEndpointToRemove
                val historyConfirmsTerminal =
                    authoritativeSessionRuns[entry.sessionId]
                        .orEmpty()
                        .any { knownRun -> knownRun.id == entry.runId && !knownRun.isActive() }
                val endpointMatches = endpoint == null || _uiState.value.endpoint == endpoint
                val historyMatches =
                    requestHistoryGeneration == null ||
                        (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) == requestHistoryGeneration ||
                        historyConfirmsTerminal
                shouldRemoveRecoveryEntry =
                    connectionGeneration == requestConnectionGeneration && endpointMatches && historyMatches
            }
        }
        if (shouldRemoveRecoveryEntry) {
            removeRecoveryEntry(
                endpoint = recoveryEndpointToRemove,
                entry = requireNotNull(recoveryEntryToRemove),
            )
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
                visibleSessionRuns(sessionId).lastOrNull { it.id == runId }
                    ?: Run(runId, sessionId, UNCERTAIN_RUN_STATUS)
            val uncertainRun = knownRun
            if (isLocallyOwnedRun(sessionId, runId)) {
                sessionRuns[sessionId] = mergeRuns(sessionRuns[sessionId].orEmpty(), listOf(uncertainRun))
            } else {
                authoritativeSessionRuns[sessionId] =
                    mergeRuns(authoritativeSessionRuns[sessionId].orEmpty(), listOf(uncertainRun))
            }
            val knownRuns = visibleSessionRuns(sessionId)
            val previous = observationStateFor(sessionId, runId)
            val uncertainState = uncertainObservationState(uncertainRun, previous)
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
                                    sendErrorCategory = opened.sendErrorCategory,
                                    hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                    latestRunState = latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
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

    private fun hasUnresolvedSubmission(sessionId: SessionId): Boolean {
        val hasUncertainRun = uncertainSubmissionRunIds.containsKey(sessionId)
        val uncertainRunId =
            if (hasUncertainRun) {
                uncertainSubmissionRunIds[sessionId]
            } else {
                null
            }
        val uncertainRun =
            uncertainRunId?.let { runId ->
                visibleSessionRuns(sessionId).lastOrNull { it.id == runId }
            }
        return unresolvedSubmissionSessions.contains(sessionId) ||
            recoveryUnavailableSessions.contains(sessionId) ||
            pendingCreateSessions.contains(recoverySessionKey(sessionId)) ||
            runSubmissionUncertaintyStore.contains(pendingRunSubmissionKey(sessionId)) ||
            !unresolvedLocalRunIds[recoverySessionKey(sessionId)].isNullOrEmpty() ||
            (
                uncertainRunId != null &&
                    (
                        sessionSendErrors[sessionId] == MessageSendErrorCategory.UNCERTAIN ||
                            uncertainRun == null ||
                            !uncertainRun.isActive()
                    )
            )
    }

    private fun sendErrorCategoryFor(sessionId: SessionId): MessageSendErrorCategory? =
        sessionSendErrors[sessionId]
            ?: MessageSendErrorCategory.UNCERTAIN.takeIf { recoveryUnavailableSessions.contains(sessionId) }
            ?: MessageSendErrorCategory.UNCERTAIN.takeIf { hasUnresolvedSubmission(sessionId) }

    private fun markTimedOutSendUncertain(
        sessionId: SessionId,
        current: SessionListUiState,
        opened: OpenSessionUiState,
        recoveryDraft: String? = null,
        authoritativeMessages: List<SessionMessageUiState>? = null,
        errorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
    ): Boolean {
        unresolvedSubmissionSessions += sessionId
        sessionSendErrors[sessionId] = errorCategory
        pendingRunDrafts.remove(sessionId)?.let { uncertainSendDrafts[sessionId] = it }
            ?: recoveryDraft?.let { uncertainSendDrafts[sessionId] = it }
        val knownRuns = visibleSessionRuns(sessionId)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                messages = authoritativeMessages ?: opened.messages,
                                isSending = false,
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState = latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                activeResponse = latestObservation?.toSessionMessageUiState(),
                                sendErrorCategory = errorCategory,
                                hasUnresolvedSubmission = true,
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
        authoritativeSessionHistoryGenerations[sessionId] =
            (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) + 1
        val incomingRuns = openedSession.history.runs()
        val incomingRunIds = incomingRuns.mapTo(mutableSetOf()) { it.id }
        authoritativeSessionRuns[sessionId] =
            authoritativeSessionRuns[sessionId]
                .orEmpty()
                .filter { it.id !in incomingRunIds } + incomingRuns
        val localRunIds = sessionRuns[sessionId].orEmpty().mapTo(mutableSetOf()) { it.id }
        val retainedLocalRuns =
            sessionRuns[sessionId].orEmpty().filter(Run::isActive) +
                allObservationStates(sessionId)
                    .filter { state -> state.run.id in localRunIds }
                    .map(RunObservationState::run)
        sessionRuns[sessionId] = mergeRuns(emptyList(), retainedLocalRuns)
        return visibleSessionRuns(sessionId)
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
                            val current = _uiState.value.sessionList
                            val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
                            if (current != null && opened?.isSending == true) {
                                _uiState.value =
                                    _uiState.value.copy(
                                        sessionList = current.copy(openedSession = opened.copy(isSending = false)),
                                    )
                            }
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
                    observationJobToCancel = runObservationJobs[sessionId]
                    observationToClose = runObservations.remove(sessionId)
                    runObservationRunIds.remove(sessionId)
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
                                val latestRun = knownRuns.latestRun()
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
                                            sendErrorCategory = sendErrorCategoryFor(sessionId),
                                            hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                            latestRun = latestRun,
                                            activeRuns = knownRuns.activeRuns(),
                                            isSending = runJobs.containsKey(sessionId),
                                            latestRunState = latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                            activeResponse = latestObservation?.toSessionMessageUiState(),
                                        ).copy(isReconciliationInProgress = openedSession.history.latestRun() != null),
                                    errorCategory = null,
                                )
                            }
                        if (applied) {
                            val pendingTimeoutRecovery =
                                synchronized(sessionRequestLock) {
                                    pendingTimedOutSends[sessionId]
                                }
                            if (pendingTimeoutRecovery != null) {
                                reconcileTimedOutSendNow(
                                    sessionId = sessionId,
                                    requestConnectionGeneration = requestConnectionGeneration,
                                    requestSessionGeneration = request.generation,
                                    knownRunIds = pendingTimeoutRecovery.knownRunIds,
                                )
                            } else {
                                val pendingSettledRecovery = prepareSettledSubmissionRecovery(sessionId)
                                if (pendingSettledRecovery != null) {
                                    reconcileTimedOutSendNow(
                                        sessionId = sessionId,
                                        requestConnectionGeneration = requestConnectionGeneration,
                                        requestSessionGeneration = request.generation,
                                        knownRunIds = pendingSettledRecovery.knownRunIds,
                                        resolveWhenNoNewRun = true,
                                    )
                                } else {
                                    reconcileOpenedRun(
                                        sessionId = sessionId,
                                        requestSessionGeneration = request.generation,
                                        requestConnectionGeneration = requestConnectionGeneration,
                                        sessionGateway = gateway,
                                        restartObservation = true,
                                        runIdToReconcile = historyRunIdToReconcile(sessionId, openedSession),
                                        clearRefreshWhenNoRun = true,
                                    )
                                }
                            }
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
                retainUnconfirmedTerminalRuns(sessionId)
                observationJobToCancel = runObservationJobs[sessionId]
                observationToClose = runObservations.remove(sessionId)
                runObservationRunIds.remove(sessionId)
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
                                    sendErrorCategory = sendErrorCategoryFor(opened.session.id),
                                ),
                        ),
                )
        }
    }

    private fun retryUncertainSubmissionOrSend() {
        val retryContext =
            synchronized(sessionRequestLock) {
                val opened = _uiState.value.sessionList?.openedSession ?: return@synchronized null
                val sessionId = opened.session.id
                val key = pendingRunSubmissionKey(sessionId)
                if (!opened.hasUnresolvedSubmission ||
                    (runSubmissionUncertaintyStore.contains(key) && !runSubmissionUncertaintyStore.isSettled(key))
                ) {
                    null
                } else {
                    Triple(sessionId, connectionGeneration, runSubmissionUncertaintyStore.knownRunIds(key))
                }
            }
        if (retryContext == null) {
            sendMessage()
            return
        }
        val (sessionId, requestConnectionGeneration, knownRunIds) = retryContext
        scope.launch {
            val reconciled =
                reconcileTimedOutSend(
                    sessionId = sessionId,
                    requestConnectionGeneration = requestConnectionGeneration,
                    knownRunIds = knownRunIds,
                    resolveWhenNoNewRun = true,
                )
            if (reconciled) {
                val canRetry =
                    synchronized(sessionRequestLock) {
                        !hasUnresolvedSubmission(sessionId)
                    }
                if (canRetry) sendMessage()
            }
        }
    }

    private fun sendMessage() {
        val gateway = runGateway ?: return
        var pendingSubmissionPersistence: PendingSubmissionPersistence? = null
        val job =
            synchronized(sessionRequestLock) {
                val current = _uiState.value.sessionList ?: return@synchronized null
                val opened = current.openedSession ?: return@synchronized null
                val sessionId = opened.session.id
                val requestConnectionGeneration = connectionGeneration
                val knownRuns =
                    visibleSessionRuns(sessionId).ifEmpty {
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
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN ||
                    opened.hasUnresolvedSubmission ||
                    opened.composerText.isBlank() ||
                    opened.isRefreshing ||
                    opened.isReconciliationInProgress ||
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
                val requestEndpoint = _uiState.value.endpoint
                val recoverySessionKey = RecoverySessionKey(requestEndpoint, sessionId)
                val knownRunIds = knownRuns.mapTo(mutableSetOf()) { it.id }
                pendingCreateSessions[recoverySessionKey] = knownRunIds
                pendingSubmissionPersistence =
                    PendingSubmissionPersistence(
                        key = PendingRunSubmissionKey(requestEndpoint, sessionId),
                        recoveryKey = recoverySessionKey,
                        knownRunIds = knownRunIds,
                    )
                createRunJob(sessionId) {
                    if (!markPendingCreateStarted(
                            recoverySessionKey,
                            knownRunIds,
                            requestConnectionGeneration,
                        )
                    ) {
                        return@createRunJob
                    }
                    try {
                        val run =
                            withContext(NonCancellable) {
                                withTimeout(sendTimeoutMillis) {
                                    runInterruptible {
                                        SubmitMessage(gateway).execute(sessionId, opened.composerText)
                                    }
                                }
                            }
                        val applied =
                            applySubmittedRun(
                                sessionId = sessionId,
                                run = run,
                                requestConnectionGeneration = requestConnectionGeneration,
                                requestEndpoint = requestEndpoint,
                            )
                        forgetPendingCreate(recoverySessionKey, knownRunIds)
                        if (applied) {
                            if (!run.isActive()) {
                                val reconciliationContext =
                                    synchronized(sessionRequestLock) {
                                        sessionGateway
                                            ?.takeIf {
                                                connectionGeneration == requestConnectionGeneration &&
                                                    _uiState.value.sessionList?.openedSession?.session?.id == sessionId
                                            }?.let { it to sessionRequestGeneration }
                                    }
                                if (reconciliationContext != null) {
                                    reconcileRun(
                                        sessionId = sessionId,
                                        runId = run.id,
                                        requestConnectionGeneration = requestConnectionGeneration,
                                        requestSessionGeneration = reconciliationContext.second,
                                        sessionGateway = reconciliationContext.first,
                                    )
                                }
                            }
                            onRunSubmissionCompleted?.invoke()
                        }
                    } catch (_: TimeoutCancellationException) {
                        runSubmissionUncertaintyStore.markAmbiguous(PendingRunSubmissionKey(requestEndpoint, sessionId))
                        forgetPendingCreate(recoverySessionKey, knownRunIds)
                        val reconciled =
                            reconcileTimedOutSend(
                                sessionId,
                                requestConnectionGeneration,
                                knownRunIds,
                            )
                        if (reconciled) {
                            onRunSubmissionCompleted?.invoke()
                        }
                    } catch (error: CancellationException) {
                        runSubmissionUncertaintyStore.markAmbiguous(PendingRunSubmissionKey(requestEndpoint, sessionId))
                        throw error
                    } catch (_: GatewayException) {
                        runSubmissionUncertaintyStore.markAmbiguous(PendingRunSubmissionKey(requestEndpoint, sessionId))
                        forgetPendingCreate(recoverySessionKey, knownRunIds)
                        val reconciled =
                            reconcileTimedOutSend(
                                sessionId = sessionId,
                                requestConnectionGeneration = requestConnectionGeneration,
                                knownRunIds = knownRunIds,
                                uncertaintyErrorCategory = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED,
                            )
                        if (reconciled) onRunSubmissionCompleted?.invoke()
                    } catch (_: Exception) {
                        runSubmissionUncertaintyStore.markAmbiguous(PendingRunSubmissionKey(requestEndpoint, sessionId))
                        forgetPendingCreate(recoverySessionKey, knownRunIds)
                        val reconciled =
                            reconcileTimedOutSend(
                                sessionId = sessionId,
                                requestConnectionGeneration = requestConnectionGeneration,
                                knownRunIds = knownRunIds,
                                uncertaintyErrorCategory = MessageSendErrorCategory.GATEWAY_REQUEST_FAILED,
                            )
                        if (reconciled) onRunSubmissionCompleted?.invoke()
                    } finally {
                        forgetPendingCreate(recoverySessionKey, knownRunIds)
                        onRunSubmissionSettled?.invoke()
                    }
                }
            }
        job?.let { runJob ->
            val pendingSubmission = pendingSubmissionPersistence ?: return@let
            try {
                runSubmissionUncertaintyStore.add(pendingSubmission.key, pendingSubmission.knownRunIds)
            } catch (_: Exception) {
                synchronized(sessionRequestLock) {
                    if (pendingCreateSessions[pendingSubmission.recoveryKey] == pendingSubmission.knownRunIds) {
                        pendingCreateSessions.remove(pendingSubmission.recoveryKey)
                        pendingCreateStarted.remove(pendingSubmission.recoveryKey)
                        unresolvedSubmissionSessions += pendingSubmission.key.sessionId
                        pendingRunDrafts.remove(pendingSubmission.key.sessionId)?.let { draft ->
                            uncertainSendDrafts[pendingSubmission.key.sessionId] = draft
                        }
                        sessionSendErrors[pendingSubmission.key.sessionId] = MessageSendErrorCategory.UNCERTAIN
                        val current = _uiState.value.sessionList
                        val opened = current?.openedSession?.takeIf { it.session.id == pendingSubmission.key.sessionId }
                        if (current != null && opened != null) {
                            _uiState.value =
                                _uiState.value.copy(
                                    sessionList =
                                        current.copy(
                                            openedSession =
                                                opened.copy(
                                                    isSending = false,
                                                    sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                                    hasUnresolvedSubmission = true,
                                                ),
                                        ),
                                )
                        }
                    }
                }
                return@let
            }
            runJob.start()
        }
    }

    private fun prepareSettledSubmissionRecovery(sessionId: SessionId): TimedOutSendRecovery? {
        return synchronized(sessionRequestLock) {
            val key = pendingRunSubmissionKey(sessionId)
            if (
                !runSubmissionUncertaintyStore.contains(key) ||
                !runSubmissionUncertaintyStore.isSettled(key) ||
                pendingTimedOutSends.containsKey(sessionId)
            ) {
                null
            } else {
                TimedOutSendRecovery(
                    knownRunIds = runSubmissionUncertaintyStore.knownRunIds(key),
                    draft = sessionDrafts[sessionId].orEmpty(),
                ).also { pendingTimedOutSends[sessionId] = it }
            }
        }
    }

    private suspend fun reconcileTimedOutSend(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        knownRunIds: Set<RunId>,
        resolveWhenNoNewRun: Boolean = false,
        uncertaintyErrorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
    ): Boolean {
        var requestEndpoint = ""
        val connectionIsCurrent =
            synchronized(sessionRequestLock) {
                if (connectionGeneration != requestConnectionGeneration) {
                    false
                } else {
                    requestEndpoint = _uiState.value.endpoint
                    pendingTimedOutSends[sessionId] =
                        TimedOutSendRecovery(
                            knownRunIds = knownRunIds,
                            draft = pendingRunDrafts[sessionId] ?: sessionDrafts[sessionId].orEmpty(),
                            errorCategory = uncertaintyErrorCategory,
                        )
                    true
                }
            }
        if (!connectionIsCurrent) return false
        val currentSessionGeneration =
            synchronized(sessionRequestLock) {
                _uiState.value.sessionList?.openedSession
                    ?.takeIf { it.session.id == sessionId }
                    ?.let { sessionRequestGeneration }
            } ?: return true
        return reconcileTimedOutSendNow(
            sessionId = sessionId,
            requestConnectionGeneration = requestConnectionGeneration,
            requestSessionGeneration = currentSessionGeneration,
            knownRunIds = knownRunIds,
            resolveWhenNoNewRun = resolveWhenNoNewRun,
            requestEndpoint = requestEndpoint,
        )
    }

    private suspend fun reconcileTimedOutSendNow(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        knownRunIds: Set<RunId>,
        resolveWhenNoNewRun: Boolean = false,
        requestEndpoint: String = _uiState.value.endpoint,
    ): Boolean {
        var beganReconciliation = false
        repeat(100) {
            if (beganReconciliation) return@repeat
            val requestIsCurrent =
                synchronized(sessionRequestLock) {
                    connectionGeneration == requestConnectionGeneration &&
                        sessionRequestGeneration == requestSessionGeneration
                }
            if (!requestIsCurrent) return false
            if (beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) {
                beganReconciliation = true
                return@repeat
            }
            delay(10)
        }
        if (!beganReconciliation) return false
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
                val boundRunId =
                    runSubmissionUncertaintyStore.boundRunId(PendingRunSubmissionKey(requestEndpoint, sessionId))
                val boundRunReconciliation =
                    boundRunId
                        ?.takeUnless { runId -> result.discoveredRuns.any { it.id == runId } }
                        ?.let { runId ->
                            runCatching { ReconcileRun(gateways.second, gateways.first).execute(runId, sessionId) }
                                .getOrNull()
                        }
                val reconciledResult =
                    boundRunReconciliation?.let { bound ->
                        result.copy(
                            history = bound.history,
                            discoveredRuns = mergeRuns(result.discoveredRuns, listOf(bound.run)),
                        )
                    } ?: result
                val outcome =
                    applyTimedOutSendReconciliation(
                        sessionId,
                        requestConnectionGeneration,
                        requestSessionGeneration,
                        reconciledResult,
                        resolveWhenNoNewRun,
                    )
                if (outcome.applied) {
                    outcome.runToPersist?.let { entry ->
                        persistOrQueueRecoveryEntry(requestEndpoint, entry)
                    }
                    outcome.runToRemove?.let { entry ->
                        removeRecoveryEntry(requestEndpoint, entry)
                    }
                    outcome.runToObserve?.let {
                        startRunObservation(
                            sessionId = sessionId,
                            run = it,
                            expectedConnectionGeneration = requestConnectionGeneration,
                            expectedSessionGeneration = requestSessionGeneration,
                        )
                    }
                }
                outcome.applied
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
        reconciliation: SessionReconciliation,
        resolveWhenNoNewRun: Boolean,
    ): TimedOutSendReconciliationOutcome =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            }
            val current = _uiState.value.sessionList ?: return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            val opened =
                current.openedSession?.takeIf { it.session.id == sessionId }
                    ?: return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            val pendingRecovery = pendingTimedOutSends[sessionId]
            val recoveryDraft = pendingRecovery?.draft
            val pendingKey = pendingRunSubmissionKey(sessionId)
            val authoritativeRuns =
                mergeRuns(reconciliation.history.runs(), reconciliation.discoveredRuns)
            val authoritativeRunIds = authoritativeRuns.mapTo(mutableSetOf()) { it.id }
            val retainedAuthoritativeRuns =
                authoritativeSessionRuns[sessionId]
                    .orEmpty()
                    .filter { existing -> existing.id !in authoritativeRunIds }
            authoritativeSessionRuns[sessionId] =
                mergeRuns(retainedAuthoritativeRuns, authoritativeRuns)
            val authoritativeMessages =
                reconciliation.history.messages
                    .map { it.toSessionMessageUiState() }
                    .chronological()
            val uncertaintyBelongsToThisSubmission =
                !runSubmissionUncertaintyStore.contains(pendingKey) ||
                    runSubmissionUncertaintyStore.knownRunIds(pendingKey) == pendingRecovery?.knownRunIds.orEmpty()
            runSubmissionUncertaintyStore
                .boundRunId(pendingKey)
                ?.let { boundRunId -> uncertainSubmissionRunIds[sessionId] = boundRunId }
            val existingBoundSubmissionRun =
                uncertainSubmissionRunIds[sessionId]?.let { runId ->
                    reconciliation.discoveredRuns.lastOrNull { it.id == runId }
                        ?: visibleSessionRuns(sessionId).lastOrNull { it.id == runId && it.isActive() }
                }
            val discoveredLocalRun: Run? = null
            if (
                existingBoundSubmissionRun == null &&
                reconciliation.discoveredRuns.isNotEmpty() &&
                uncertaintyBelongsToThisSubmission
            ) {
                ambiguousSubmissionSessions += sessionId
                runSubmissionUncertaintyStore.markAmbiguous(pendingKey)
            }
            val submissionRun = existingBoundSubmissionRun ?: discoveredLocalRun
            val terminalRunIds =
                reconciliation.discoveredRuns
                    .filterNot(Run::isActive)
                    .map { it.id }
                    .plus(submissionRun?.takeUnless(Run::isActive)?.id)
                    .filterNotNull()
                    .toSet()
            terminalRunIds.forEach { runId -> forgetUnresolvedLocalRun(sessionId, runId) }
            val submissionConfirmed =
                submissionRun != null &&
                    !submissionRun.isActive() &&
                    uncertaintyBelongsToThisSubmission
            val recoveryStoreAllowsNoNewRun =
                !runSubmissionUncertaintyStore.contains(pendingKey) ||
                    (
                        runSubmissionUncertaintyStore.isSettled(pendingKey) &&
                            !runSubmissionUncertaintyStore.requiresRunMatch(pendingKey)
                    )
            val noNewRunConfirmsNoSubmission =
                resolveWhenNoNewRun &&
                    sessionId !in ambiguousSubmissionSessions &&
                    recoveryStoreAllowsNoNewRun &&
                    uncertainSubmissionRunIds[sessionId] == null
            val canClearUncertainty =
                uncertaintyBelongsToThisSubmission &&
                    (submissionConfirmed || noNewRunConfirmsNoSubmission)
            if (canClearUncertainty) {
                unresolvedSubmissionSessions.remove(sessionId)
                ambiguousSubmissionSessions.remove(sessionId)
                uncertainSubmissionRunIds.remove(sessionId)
                if (submissionRun != null) {
                    forgetObservationState(sessionId, submissionRun.id)
                }
                val draft = recoveryDraft ?: uncertainSendDrafts[sessionId]
                uncertainSendDrafts.remove(sessionId)
                if (draft != null && sessionDrafts[sessionId] == draft) sessionDrafts[sessionId] = draft
                val clearedSendErrorCategory =
                    if (resolveWhenNoNewRun) {
                        MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
                    } else {
                        pendingRecovery?.errorCategory ?: MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
                    }
                sessionSendErrors[sessionId] =
                    clearedSendErrorCategory
                runSubmissionUncertaintyStore.removeIfKnownRunIdsMatch(
                    pendingKey,
                    pendingRecovery?.knownRunIds.orEmpty(),
                )
                val knownRuns = visibleSessionRuns(sessionId)
                val latestObservation = latestObservationState(sessionId, knownRuns)
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        messages = authoritativeMessages,
                                        composerText = sessionDrafts[sessionId].orEmpty(),
                                        isSending = false,
                                        latestRun = knownRuns.latestRun(),
                                        activeRuns = knownRuns.activeRuns(),
                                        latestRunState = latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                        activeResponse = latestObservation?.toSessionMessageUiState(),
                                        sendErrorCategory = clearedSendErrorCategory,
                                        hasUnresolvedSubmission = false,
                                        errorCategory = null,
                                        isStale = false,
                                    ),
                            ),
                    )
            } else {
                markTimedOutSendUncertain(
                    sessionId = sessionId,
                    current = current,
                    opened = opened,
                    recoveryDraft = recoveryDraft,
                    authoritativeMessages = authoritativeMessages,
                    errorCategory = pendingRecovery?.errorCategory ?: MessageSendErrorCategory.UNCERTAIN,
                )
            }
            pendingTimedOutSends.remove(sessionId)
            TimedOutSendReconciliationOutcome(
                applied = true,
                runToObserve = (existingBoundSubmissionRun ?: discoveredLocalRun)?.takeIf(Run::isActive),
                runToPersist =
                    discoveredLocalRun
                        ?.takeIf(Run::isActive)
                        ?.let { RunRecoveryEntry(sessionId, it.id) },
                runToRemove =
                    submissionRun
                        ?.takeUnless(Run::isActive)
                        ?.let { RunRecoveryEntry(sessionId, it.id) },
            )
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
            val recoveryDraft = pendingTimedOutSends[sessionId]?.draft
            markTimedOutSendUncertain(
                sessionId,
                current,
                opened,
                recoveryDraft,
                errorCategory = pendingTimedOutSends[sessionId]?.errorCategory ?: MessageSendErrorCategory.UNCERTAIN,
            )
            pendingTimedOutSends.remove(sessionId)
        }
    }

    private fun persistDisconnectedRecoveryEntry(
        endpoint: String,
        entry: RunRecoveryEntry,
    ): Boolean {
        val persisted = persistOrQueueRecoveryEntry(endpoint, entry)
        if (persisted) {
            reconcilePersistedRecoveryEntryIfConnected(endpoint, entry)
        }
        return persisted
    }

    private fun reconcilePersistedRecoveryEntryIfConnected(
        endpoint: String,
        entry: RunRecoveryEntry,
    ) {
        val recoveryContext =
            synchronized(sessionRequestLock) {
                val currentSessionGateway = sessionGateway
                val currentRunGateway = runGateway
                if (
                    !_uiState.value.isConnected ||
                    _uiState.value.endpoint != endpoint ||
                    currentSessionGateway == null ||
                    currentRunGateway == null
                ) {
                    null
                } else {
                    val opened =
                        _uiState.value.sessionList?.openedSession?.takeIf { it.session.id == entry.sessionId }
                    RecoveryRunContext(
                        sessionGateway = currentSessionGateway,
                        runGateway = currentRunGateway,
                        connectionGeneration = connectionGeneration,
                        historyGeneration = authoritativeSessionHistoryGenerations[entry.sessionId] ?: 0L,
                        sessionGeneration = opened?.let { sessionRequestGeneration },
                        updateVisibleUi = opened != null,
                    )
                }
            } ?: return
        lateinit var job: Job
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != recoveryContext.connectionGeneration ||
                _uiState.value.endpoint != endpoint
            ) {
                return
            }
            job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        recoverRun(
                            entry = entry,
                            sessionGateway = recoveryContext.sessionGateway,
                            runGateway = recoveryContext.runGateway,
                            expectedConnectionGeneration = recoveryContext.connectionGeneration,
                            expectedHistoryGeneration = recoveryContext.historyGeneration,
                            requestSessionGeneration = recoveryContext.sessionGeneration,
                            updateVisibleUi = recoveryContext.updateVisibleUi,
                        )
                    } finally {
                        synchronized(sessionRequestLock) { connectionRecoveryJobs.remove(job) }
                    }
                }
            connectionRecoveryJobs += job
        }
        job.start()
    }

    private fun applySubmittedRun(
        sessionId: SessionId,
        run: Run,
        requestConnectionGeneration: Long,
        requestEndpoint: String,
    ): Boolean {
        var shouldObserve = false
        var persistAfterDisconnect = false
        var removeSubmissionUncertainty = false
        var requestSessionGeneration = 0L
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        val pendingKey = PendingRunSubmissionKey(requestEndpoint, sessionId)
        val submissionBindingFailed =
            runCatching { runSubmissionUncertaintyStore.bindRun(pendingKey, run.id) }.isFailure
        val persistBeforeApply =
            run.isActive() &&
                synchronized(sessionRequestLock) {
                    connectionGeneration == requestConnectionGeneration
                }
        val recoveryEntryPersisted =
            persistBeforeApply &&
                persistOrQueueRecoveryEntry(
                    requestEndpoint,
                    RunRecoveryEntry(sessionId = sessionId, runId = run.id),
                )
        val recoveryPersistenceFailed =
            (run.isActive() && persistBeforeApply && !recoveryEntryPersisted) ||
                (!run.isActive() && submissionBindingFailed)
        val applied =
            synchronized(sessionRequestLock) {
                if (connectionGeneration != requestConnectionGeneration) {
                    persistAfterDisconnect = run.isActive()
                    if (run.isActive()) {
                        unresolvedLocalRunIds
                            .getOrPut(RecoverySessionKey(requestEndpoint, sessionId), ::mutableSetOf)
                            .add(run.id)
                    }
                    return@synchronized false
                }
                requestSessionGeneration = sessionRequestGeneration
                shouldObserve = run.isActive()
                if (recoveryPersistenceFailed) {
                    runSubmissionUncertaintyStore.markAmbiguous(pendingKey)
                }
                removeSubmissionUncertainty = run.isActive() && !recoveryPersistenceFailed
                uncertainSubmissionRunIds[sessionId] = run.id
                val submittedDraft = pendingRunDrafts.remove(sessionId)
                if (recoveryPersistenceFailed) {
                    submittedDraft?.let { uncertainSendDrafts[sessionId] = it }
                    sessionSendErrors[sessionId] = MessageSendErrorCategory.UNCERTAIN
                } else if (sessionDrafts[sessionId] == submittedDraft) {
                    sessionSendErrors.remove(sessionId)
                    sessionDrafts.remove(sessionId)
                } else {
                    sessionSendErrors.remove(sessionId)
                }
                sessionRuns[sessionId] =
                    (sessionRuns[sessionId].orEmpty().filterNot { it.id == run.id } + run)
                val knownRuns = visibleSessionRuns(sessionId)
                val observationState =
                    if (run.isActive() && !recoveryPersistenceFailed) {
                        observationStateFor(sessionId, run.id) ?: RunEventStateTransition.initial(run)
                    } else {
                        uncertainObservationState(run, observationStateFor(sessionId, run.id))
                    }
                rememberObservationState(observationState)
                val current = _uiState.value.sessionList
                val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
                if (current != null && opened != null) {
                    observationJobToCancel = runObservationJobs[sessionId]
                    observationToClose = runObservations.remove(sessionId)
                    runObservationRunIds.remove(sessionId)
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
                                            sendErrorCategory =
                                                if (recoveryPersistenceFailed) {
                                                    MessageSendErrorCategory.UNCERTAIN
                                                } else {
                                                    null
                                                },
                                            hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                            latestRunState = observationState.state,
                                            activeResponse = observationState.toSessionMessageUiState(),
                                            isReconciliationInProgress = !run.isActive(),
                                            isStale = !run.isActive() || recoveryPersistenceFailed,
                                        ),
                                ),
                        )
                }
                true
            }
        if (!applied && persistAfterDisconnect && !persistBeforeApply) {
            val entry = RunRecoveryEntry(sessionId = sessionId, runId = run.id)
            if (run.isActive()) {
                removeSubmissionUncertainty =
                    persistDisconnectedRecoveryEntry(endpoint = requestEndpoint, entry = entry)
            } else {
                reconcilePersistedRecoveryEntryIfConnected(requestEndpoint, entry)
            }
        }
        if (removeSubmissionUncertainty) {
            runSubmissionUncertaintyStore.remove(pendingKey)
        }
        observationToClose?.close()
        observationJobToCancel?.cancel()
        if (applied && shouldObserve) {
            startRunObservation(
                sessionId = sessionId,
                run = run,
                expectedConnectionGeneration = requestConnectionGeneration,
                expectedSessionGeneration = requestSessionGeneration,
            )
        }
        return applied
    }

    private fun startRunObservation(
        sessionId: SessionId,
        run: Run,
        expectedConnectionGeneration: Long? = null,
        expectedSessionGeneration: Long? = null,
    ) {
        var jobToStart: Job? = null
        var observerJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        var handoffRequested = false
        synchronized(sessionRequestLock) {
            val currentConnectionGeneration = connectionGeneration
            val currentSessionGeneration = sessionRequestGeneration
            if (
                (expectedConnectionGeneration != null && expectedConnectionGeneration != currentConnectionGeneration) ||
                (expectedSessionGeneration != null && expectedSessionGeneration != currentSessionGeneration)
            ) {
                return
            }
            val current = _uiState.value.sessionList
            val opened = current?.openedSession
            if (
                opened == null ||
                opened.session.id != sessionId ||
                !run.isActive() ||
                sessionGateway == null ||
                runGateway == null
            ) {
                return
            }
            val startRequest =
                ObserverStartRequest(
                    run = run,
                    connectionGeneration = currentConnectionGeneration,
                    sessionGeneration = currentSessionGeneration,
                )
            val existingJob = runObservationJobs[sessionId]
            if (existingJob != null) {
                if (runObservationRunIds[sessionId] != run.id || existingJob.isCancelled) {
                    pendingRunObservationRequests[sessionId] = startRequest
                }
                if (runObservationRunIds[sessionId] != run.id) {
                    observerJobToCancel = existingJob
                    observationToClose = runObservations.remove(sessionId)
                    runObservationRunIds.remove(sessionId)
                }
                handoffRequested = true
            } else {
                pendingRunObservationRequests.remove(sessionId)
                val state =
                    observationStateFor(sessionId, run.id)
                        ?: RunEventStateTransition.initial(run)
                rememberObservationState(state)
                val knownRuns = visibleSessionRuns(sessionId)
                val latestRun = knownRuns.latestRun()
                val latestObservation = latestObservationState(sessionId, knownRuns)
                val latestState =
                    latestObservation?.state
                        ?: state.takeIf { latestRun == null || latestRun.id == run.id }?.state
                        ?: latestRun?.toRunPresentationState()
                val latestResponse =
                    latestObservation?.toSessionMessageUiState()
                        ?: state.takeIf { latestRun == null || latestRun.id == run.id }?.toSessionMessageUiState()
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        latestRunState = latestState,
                                        activeResponse = latestResponse,
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
                            val restartRequest =
                                synchronized(sessionRequestLock) {
                                    if (runObservationJobs[sessionId] !== observationJob) {
                                        null
                                    } else {
                                        runObservationJobs.remove(sessionId)
                                        runObservationRunIds.remove(sessionId)
                                        val pendingRequest = pendingRunObservationRequests.remove(sessionId)
                                        val nextRun =
                                            pendingRequest?.run?.takeIf(Run::isActive)
                                                ?: visibleSessionRuns(sessionId).latestActiveRun()
                                        if (!observationJob.isCancelled && pendingRequest == null) {
                                            null
                                        } else {
                                            val current = _uiState.value.sessionList
                                            val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
                                            if (opened == null || nextRun == null || runGateway == null || sessionGateway == null) {
                                                null
                                            } else {
                                                ObserverStartRequest(
                                                    run = nextRun,
                                                    connectionGeneration = connectionGeneration,
                                                    sessionGeneration = sessionRequestGeneration,
                                                )
                                            }
                                        }
                                    }
                                }
                            restartRequest?.let {
                                startRunObservation(
                                    sessionId = sessionId,
                                    run = it.run,
                                    expectedConnectionGeneration = it.connectionGeneration,
                                    expectedSessionGeneration = it.sessionGeneration,
                                )
                            }
                        }
                    }
                runObservationJobs[sessionId] = observationJob
                runObservationRunIds[sessionId] = run.id
                jobToStart = observationJob
            }
        }
        observationToClose?.close()
        observerJobToCancel?.cancel()
        if (handoffRequested) return
        val observationJob = jobToStart ?: return
        if (!observationJob.start()) {
            val restartRequest =
                synchronized(sessionRequestLock) {
                    if (runObservationJobs[sessionId] !== observationJob) {
                        null
                    } else {
                        runObservationJobs.remove(sessionId)
                        runObservationRunIds.remove(sessionId)
                        val pendingRequest = pendingRunObservationRequests.remove(sessionId)
                        val nextRun =
                            pendingRequest?.run?.takeIf(Run::isActive)
                                ?: visibleSessionRuns(sessionId).latestActiveRun()
                        nextRun?.let {
                            if ((observationJob.isCancelled || pendingRequest != null) &&
                                _uiState.value.sessionList?.openedSession?.session?.id == sessionId &&
                                runGateway != null &&
                                sessionGateway != null
                            ) {
                                ObserverStartRequest(it, connectionGeneration, sessionRequestGeneration)
                            } else {
                                null
                            }
                        }
                    }
                }
            restartRequest?.let {
                startRunObservation(
                    sessionId = sessionId,
                    run = it.run,
                    expectedConnectionGeneration = it.connectionGeneration,
                    expectedSessionGeneration = it.sessionGeneration,
                )
            }
        }
    }

    private suspend fun observeRun(
        sessionId: SessionId,
        run: Run,
    ) {
        val observationJob = currentCoroutineContext()[Job] ?: return
        var observation: RunEventObservation? = null
        var lateObservation: RunEventObservation? = null
        var shouldReconcile = false
        try {
            currentCoroutineContext().ensureActive()
            val gateway =
                synchronized(sessionRequestLock) {
                    runGateway?.takeIf {
                        _uiState.value.sessionList?.openedSession?.session?.id == sessionId
                    }
                } ?: return
            observation =
                runInterruptible {
                    ObserveRun(gateway).execute(run.id).also { lateObservation = it }
                }
            val activeObservation = observation ?: return
            val shouldRegisterObservation =
                synchronized(sessionRequestLock) {
                    if (
                        !observationJob.isActive ||
                        _uiState.value.sessionList?.openedSession?.session?.id != sessionId ||
                        runObservationJobs[sessionId] !== observationJob ||
                        runObservationRunIds[sessionId] != run.id
                    ) {
                        false
                    } else {
                        runObservations[sessionId] = activeObservation
                        true
                    }
                }
            if (!shouldRegisterObservation) return
            var reachedTerminalState = false
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
            if (observation == null) {
                lateObservation?.close()
            } else {
                observation?.close()
            }
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
            currentCoroutineContext().ensureActive()
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
                !observationJob.isActive ||
                runObservationJobs[sessionId] !== observationJob ||
                _uiState.value.sessionList?.openedSession?.session?.id != sessionId ||
                runObservationRunIds[sessionId] != runId ||
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
            if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != event.runId) return
            val previous = observationStateFor(sessionId, event.runId) ?: return
            val next = RunEventStateTransition.apply(previous, event)
            rememberObservationState(next)
            val submissionHasUnresolvedMarker =
                runSubmissionUncertaintyStore.contains(pendingRunSubmissionKey(sessionId)) ||
                    unresolvedSubmissionSessions.contains(sessionId) ||
                    sessionSendErrors[sessionId] == MessageSendErrorCategory.UNCERTAIN
            val submissionAwaitingConfirmation =
                (next.state.isTerminal() || !next.run.isActive()) &&
                    uncertainSubmissionRunIds[sessionId] == event.runId &&
                    submissionHasUnresolvedMarker
            if (next.state.isTerminal() || !next.run.isActive()) {
                forgetUnresolvedLocalRun(sessionId, next.run.id)
            }
            if (isLocallyOwnedRun(sessionId, event.runId)) {
                sessionRuns[sessionId] =
                    mergeRuns(
                        sessionRuns[sessionId].orEmpty(),
                        listOf(next.run),
                    )
            } else {
                authoritativeSessionRuns[sessionId] =
                    mergeRuns(
                        authoritativeSessionRuns[sessionId].orEmpty(),
                        listOf(next.run),
                    )
            }
            val knownRuns = visibleSessionRuns(sessionId)
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val latestRun = knownRuns.latestRun() ?: next.run
            val latestObservation = latestObservationState(sessionId, knownRuns)
            val terminal = next.state.isTerminal() || !next.run.isActive()
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
                                    isRefreshing = opened.isRefreshing || terminal,
                                    isStale = opened.isStale || terminal,
                                    isReconciliationInProgress =
                                        opened.isReconciliationInProgress || terminal,
                                    composerText = opened.composerText,
                                    sendErrorCategory =
                                        if (submissionAwaitingConfirmation) {
                                            MessageSendErrorCategory.UNCERTAIN
                                        } else {
                                            opened.sendErrorCategory
                                        },
                                    hasUnresolvedSubmission =
                                        if (submissionAwaitingConfirmation) true else opened.hasUnresolvedSubmission,
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
            if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != runId) return
            val previous = observationStateFor(sessionId, runId) ?: return
            if (previous.state.isTerminal() || !previous.run.isActive()) return
            val next = RunEventStateTransition.interrupted(previous)
            rememberObservationState(next)
            val current = _uiState.value.sessionList ?: return
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
            val knownRuns = visibleSessionRuns(sessionId)
            val latestRun = knownRuns.latestRun()
            val latestObservation = latestObservationState(sessionId, knownRuns)
            val latestState =
                latestObservation?.state
                    ?: next.state.takeIf { latestRun?.id == runId }
                    ?: latestRun?.toRunPresentationState()
            val latestResponse =
                latestObservation?.toSessionMessageUiState()
                    ?: next.takeIf { latestRun?.id == runId }?.toSessionMessageUiState()
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRunState = latestState,
                                    activeResponse = latestResponse,
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
        return runs.latestRun()?.let { states[it.id] }
    }

    private fun latestObservedObservationState(
        sessionId: SessionId,
        runs: List<Run>,
    ): RunObservationState? {
        val states = runObservationStates[sessionId] ?: return null
        return runs.asReversed().firstOrNull { states.containsKey(it.id) }?.let { states[it.id] }
    }

    private fun forgetConfirmedObservationStates(
        sessionId: SessionId,
        confirmedRunIds: Set<RunId>,
    ) {
        if (confirmedRunIds.isEmpty()) return
        val states = runObservationStates[sessionId] ?: return
        states.keys.removeAll(confirmedRunIds)
        if (states.isEmpty()) {
            runObservationStates.remove(sessionId)
        }
    }

    private fun retainUnconfirmedTerminalRuns(sessionId: SessionId) {
        val states = runObservationStates[sessionId].orEmpty().values.toList()
        val terminalStates = states.filter { state -> state.state.isTerminal() || !state.run.isActive() }
        val locallyOwnedTerminalStates = terminalStates.filter { state -> isLocallyOwnedRun(sessionId, state.run.id) }
        terminalStates
            .filterNot { state -> state.run.id in locallyOwnedTerminalStates.mapTo(mutableSetOf()) { it.run.id } }
            .forEach { state -> forgetObservationState(sessionId, state.run.id) }
        if (locallyOwnedTerminalStates.isEmpty()) return
        locallyOwnedTerminalStates.forEach { state -> rememberObservationState(uncertainObservationState(state.run, state)) }
        sessionRuns[sessionId] =
            mergeRuns(sessionRuns[sessionId].orEmpty(), locallyOwnedTerminalStates.map(RunObservationState::run))
    }

    private fun allObservationStates(sessionId: SessionId): List<RunObservationState> =
        runObservationStates[sessionId]?.values?.toList().orEmpty()

    private fun visibleSessionRuns(sessionId: SessionId): List<Run> {
        val runs =
            mergeRuns(
                authoritativeSessionRuns[sessionId].orEmpty(),
                sessionRuns[sessionId].orEmpty(),
            )
        val unresolvedRunIds =
            buildSet {
                uncertainSubmissionRunIds[sessionId]?.let(::add)
                unresolvedLocalRunIds[recoverySessionKey(sessionId)].orEmpty().forEach(::add)
            }
        return runs.filter { it.id !in unresolvedRunIds } + runs.filter { it.id in unresolvedRunIds }
    }

    private fun isLocallyOwnedRun(
        sessionId: SessionId,
        runId: RunId,
    ): Boolean =
        sessionRuns[sessionId].orEmpty().any { it.id == runId } ||
            uncertainSubmissionRunIds[sessionId] == runId ||
            unresolvedLocalRunIds[recoverySessionKey(sessionId)]?.contains(runId) == true

    private fun historyRunIdToReconcile(
        sessionId: SessionId,
        openedSession: OpenedSession,
    ): RunId? =
        synchronized(sessionRequestLock) {
            val hasActiveLocalRun = sessionRuns[sessionId].orEmpty().any(Run::isActive)
            openedSession.history.latestRun()?.id?.takeUnless {
                hasUnresolvedSubmission(sessionId) && !hasActiveLocalRun
            }
        }

    private fun recoverySessionKey(
        sessionId: SessionId,
        endpoint: String = _uiState.value.endpoint,
    ): RecoverySessionKey = RecoverySessionKey(endpoint, sessionId)

    private fun pendingRunSubmissionKey(
        sessionId: SessionId,
        endpoint: String = _uiState.value.endpoint,
    ): PendingRunSubmissionKey = PendingRunSubmissionKey(endpoint, sessionId)

    private fun forgetPendingRecoveryEntry(
        endpoint: String?,
        entry: RunRecoveryEntry,
    ) {
        if (endpoint == null) return
        pendingDisconnectedRecoveryEntries[endpoint]?.remove(entry)
        if (pendingDisconnectedRecoveryEntries[endpoint].isNullOrEmpty()) {
            pendingDisconnectedRecoveryEntries.remove(endpoint)
        }
    }

    private fun forgetUnresolvedLocalRun(
        sessionId: SessionId,
        runId: RunId,
    ) {
        val key = recoverySessionKey(sessionId)
        unresolvedLocalRunIds[key]?.remove(runId)
        if (unresolvedLocalRunIds[key].isNullOrEmpty()) {
            unresolvedLocalRunIds.remove(key)
        }
    }

    private fun markPendingCreateStarted(
        key: RecoverySessionKey,
        knownRunIds: Set<RunId>,
        expectedConnectionGeneration: Long,
    ): Boolean =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != expectedConnectionGeneration ||
                pendingCreateSessions[key] != knownRunIds
            ) {
                false
            } else {
                pendingCreateStarted += key
                true
            }
        }

    private fun forgetPendingCreate(
        key: RecoverySessionKey,
        knownRunIds: Set<RunId>,
    ) {
        synchronized(sessionRequestLock) {
            if (pendingCreateSessions[key] == knownRunIds) {
                pendingCreateSessions.remove(key)
                pendingCreateStarted.remove(key)
            }
        }
    }
}

private fun OpenedSession.toOpenSessionUiState(
    composerText: String = "",
    sendErrorCategory: MessageSendErrorCategory? = null,
    hasUnresolvedSubmission: Boolean = false,
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
        hasUnresolvedSubmission = hasUnresolvedSubmission,
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

private fun List<Run>.latestActiveRun(): Run? = lastOrNull(Run::isActive)

private fun List<Run>.activeRuns(): List<Run> = filter(Run::isActive)

private fun mergeRuns(
    existing: List<Run>,
    incoming: List<Run>,
): List<Run> =
    (existing + incoming)
        .associateBy { it.id }
        .values
        .toList()

private data class RecoveryRunContext(
    val sessionGateway: SessionGatewayPort,
    val runGateway: RunGatewayPort,
    val connectionGeneration: Long,
    val historyGeneration: Long,
    val sessionGeneration: Long?,
    val updateVisibleUi: Boolean,
)

private data class RecoverySessionKey(
    val endpoint: String,
    val sessionId: SessionId,
)

private data class PendingSubmissionPersistence(
    val key: PendingRunSubmissionKey,
    val recoveryKey: RecoverySessionKey,
    val knownRunIds: Set<RunId>,
)

private data class ObserverStartRequest(
    val run: Run,
    val connectionGeneration: Long,
    val sessionGeneration: Long,
)

private data class ReconciliationRequest(
    val connectionGeneration: Long,
    val sessionGeneration: Long,
)

private data class TimedOutSendRecovery(
    val knownRunIds: Set<RunId>,
    val draft: String,
    val errorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
)

private data class TimedOutSendReconciliationOutcome(
    val applied: Boolean,
    val runToObserve: Run? = null,
    val runToPersist: RunRecoveryEntry? = null,
    val runToRemove: RunRecoveryEntry? = null,
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
