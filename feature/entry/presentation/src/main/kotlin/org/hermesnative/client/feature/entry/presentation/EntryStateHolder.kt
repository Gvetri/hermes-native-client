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
import kotlinx.coroutines.flow.update
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
import org.hermesnative.client.feature.entry.domain.GatewayConnectionPersistenceException
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEvent
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExportResult
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExporter
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsRecorder
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsStore
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
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
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationPermission
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationSettingsStore
import org.hermesnative.client.feature.entry.domain.RunStatusNotifier
import org.hermesnative.client.feature.entry.domain.RunSubmissionState
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionReconciliation
import org.hermesnative.client.feature.entry.domain.decideRunReconciliation
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isNotifiableTerminal
import org.hermesnative.client.feature.entry.domain.isRunRetryEligible
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.isValid
import org.hermesnative.client.feature.entry.domain.runs
import org.hermesnative.client.feature.entry.domain.shouldPostRunStatusNotification
import org.hermesnative.client.feature.entry.domain.toRunPresentationState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EntryUiEvent {
    data object AddGatewayConnectionClicked : EntryUiEvent

    data object ChangeGatewayCredentialClicked : EntryUiEvent

    data object CancelGatewayCredentialChangeClicked : EntryUiEvent

    data class EndpointChanged(
        val value: String,
    ) : EntryUiEvent

    data class BearerCredentialChanged(
        val value: String,
    ) : EntryUiEvent

    data class SaveCredentialChanged(
        val value: Boolean,
    ) : EntryUiEvent

    data object VerifyGatewayConnectionClicked : EntryUiEvent

    data object TryAgainClicked : EntryUiEvent

    data object RefreshSessionsClicked : EntryUiEvent

    data object RefreshSessionListClicked : EntryUiEvent

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

    data class RetryRunClicked(
        val runId: RunId,
    ) : EntryUiEvent

    data object RemoveGatewayConnectionClicked : EntryUiEvent

    data object RunStatusNotificationsToggleClicked : EntryUiEvent

    data class RunStatusNotificationPermissionResult(
        val granted: Boolean,
    ) : EntryUiEvent

    data object OpenLocalDiagnosticsClicked : EntryUiEvent

    data object CloseLocalDiagnosticsClicked : EntryUiEvent

    data object ExportDiagnosticsClicked : EntryUiEvent

    data object ClearDiagnosticsClicked : EntryUiEvent

    data object ConfirmClearDiagnosticsClicked : EntryUiEvent

    data object CancelClearDiagnosticsClicked : EntryUiEvent
}

enum class EntryErrorCategory(
    val safeMessage: String,
) {
    INVALID_ADDRESS("Invalid Gateway address. Enter one HTTPS Gateway endpoint."),
    SECURE_CONNECTION_FAILED("Secure connection failed. Check the Gateway certificate and hostname."),
    AUTHENTICATION_FAILED("Authentication failed. Check the Gateway credential."),
    REQUIRED_FEATURE_UNAVAILABLE("Required feature unavailable. This Gateway does not support the client contract."),
    GATEWAY_REQUEST_FAILED("Gateway request failed. Try again."),
    CREDENTIAL_STORAGE_FAILED("Could not save the Gateway credential securely. Try again."),
}

private const val DEFAULT_SEND_TIMEOUT_MILLIS = 30_000L
private const val RECOVERY_CLAIM_ATTEMPTS = 100
private const val RECOVERY_CLAIM_RETRY_MILLIS = 10L
private const val UNCERTAIN_RUN_STATUS = "uncertain"
private const val RECOVERY_PENDING_STATUS = "recovery_pending"

object NoOpRunSubmissionUncertaintyStore : RunSubmissionUncertaintyStore {
    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ) = true

    override fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ) = true

    override fun contains(key: PendingRunSubmissionKey): Boolean = false

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean = true

    override fun removeIfSnapshotMatches(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ): Boolean = true
}

data class EntryUiState(
    val title: String,
    val supportingText: String,
    val actionLabel: String,
    val connectionSetupRequested: Boolean = false,
    val isChangingCredential: Boolean = false,
    val endpoint: String = "",
    val bearerCredential: String = "",
    val saveCredential: Boolean = false,
    val isVerifying: Boolean = false,
    val isConnected: Boolean = false,
    val errorCategory: EntryErrorCategory? = null,
    val sessionList: SessionListUiState? = null,
    val runStatusNotifications: RunStatusNotificationsUiState = RunStatusNotificationsUiState(),
    val localDiagnostics: LocalDiagnosticsUiState = LocalDiagnosticsUiState(),
    val isGatewayConnectionConfigured: Boolean = false,
)

/**
 * State of the Local diagnostics surface: a private, redacted buffer of approved
 * operational records with explicit export and clear actions.
 */
data class LocalDiagnosticsUiState(
    val isOpen: Boolean = false,
    val isLoadingRecords: Boolean = false,
    val recordCount: Int = 0,
    val isExporting: Boolean = false,
    val exportFailure: LocalDiagnosticsExportFailure? = null,
    val isClearConfirmationOpen: Boolean = false,
) {
    /** True when the buffer holds records, so an export can produce a snapshot. */
    val hasRecords: Boolean
        get() = recordCount > 0

    /** Export stays disabled until the buffer holds records and no export is running. */
    val isExportEnabled: Boolean
        get() = hasRecords && !isExporting
}

/**
 * The ports the Local diagnostics surface needs, grouped so one buffer collection
 * serves all of them: the recorder appends approved events, the store reports and
 * clears them, and the exporter shares a snapshot of what the buffer holds.
 */
data class LocalDiagnosticsPorts(
    val recorder: LocalDiagnosticsRecorder,
    val store: LocalDiagnosticsStore,
    val exporter: LocalDiagnosticsExporter,
)

/** Recoverable failure shown after an export that produced no snapshot. */
enum class LocalDiagnosticsExportFailure(
    val safeMessage: String,
) {
    EXPORT_FAILED("Diagnostics export failed. The buffered diagnostics are preserved. Try again."),
}

data class RunStatusNotificationsUiState(
    val enabled: Boolean = false,
    val explanation: RunStatusNotificationExplanation? = null,
)

enum class RunStatusNotificationExplanation(
    val safeMessage: String,
) {
    PERMISSION_DENIED(
        "Notifications are blocked for this app. Allow notifications in Android settings, " +
            "then enable this option again.",
    ),
}

private fun SessionMutationUiState.hasNoPendingWork(): Boolean =
    rename == null && delete == null && pendingAction == null && errorCategory == null && retryAction == null

private inline fun handled(block: () -> Unit): Boolean {
    block()
    return true
}

private data class ReleasedConnectionState(
    val jobs: List<Job>,
    val observations: List<RunEventObservation>,
)

data class EntryStateHolderDependencies(
    val runRecoveryRegistry: RunRecoveryRegistry? = null,
    val updateRunRecoveryEndpoint: ((String?) -> Unit)? = null,
    val persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    val removeRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    val removeGatewayConnectionUseCase: RemoveGatewayConnection? = null,
    val runSubmissionUncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
    val onRunSubmissionCompleted: (() -> Unit)? = null,
    val onRunSubmissionSettled: (() -> Unit)? = null,
    val runStatusNotificationSettingsStore: RunStatusNotificationSettingsStore? = null,
    val runStatusNotificationPermission: RunStatusNotificationPermission? = null,
    val runStatusNotifier: RunStatusNotifier? = null,
    val localDiagnostics: LocalDiagnosticsPorts? = null,
    val sendTimeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
)

class EntryStateHolder(
    initialState: EntryState,
    private val verifyGatewayConnection: VerifyGatewayConnection? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sessionGatewayFactory: ((endpoint: String, bearerCredential: String) -> SessionGatewayPort)? = null,
    private val runGatewayFactory: ((endpoint: String, bearerCredential: String) -> RunGatewayPort)? = null,
    dependencies: EntryStateHolderDependencies = EntryStateHolderDependencies(),
) {
    private val runRecoveryRegistry = dependencies.runRecoveryRegistry
    private val updateRunRecoveryEndpoint = dependencies.updateRunRecoveryEndpoint
    private val persistRunRecoveryEntry = dependencies.persistRunRecoveryEntry
    private val removeRunRecoveryEntry = dependencies.removeRunRecoveryEntry
    private val removeGatewayConnectionUseCase = dependencies.removeGatewayConnectionUseCase
    private val runSubmissionUncertaintyStore = dependencies.runSubmissionUncertaintyStore
    private val onRunSubmissionCompleted = dependencies.onRunSubmissionCompleted
    private val onRunSubmissionSettled = dependencies.onRunSubmissionSettled
    private val runStatusNotificationSettingsStore = dependencies.runStatusNotificationSettingsStore
    private val runStatusNotificationPermission = dependencies.runStatusNotificationPermission
    private val runStatusNotifier = dependencies.runStatusNotifier
    private val localDiagnostics = dependencies.localDiagnostics
    private val sendTimeoutMillis = dependencies.sendTimeoutMillis

    /**
     * Requests the Android notification permission. Assigned by the app
     * composition root before the Settings control is reachable.
     */
    var requestRunStatusNotificationPermission: (() -> Unit)? = null

    init {
        require(sendTimeoutMillis > 0) { "sendTimeoutMillis must be positive." }
    }

    private val _uiState =
        MutableStateFlow(
            initialState.toUiState().copy(runStatusNotifications = restoredRunStatusNotificationState()),
        )
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()
    private var verificationJob: Job? = null
    private var sessionJob: Job? = null
    private var recoveryJob: Job? = null
    private var sessionGateway: SessionGatewayPort? = null
    private var runGateway: RunGatewayPort? = null
    private val sessionRequestLock = Any()
    private val recoveryPersistenceLock = Any()
    private val connectionPersistenceLock = Any()
    private val localDiagnosticsClearInFlight = AtomicBoolean(false)
    private var sessionRequestGeneration = 0L
    private var connectionGeneration = 0L
    private val mutationJobs = mutableMapOf<SessionId, Job>()
    private var nextMutationAttemptId = 0L
    private val mutationOwners = mutableMapOf<SessionId, SessionMutationRequest>()
    private val runJobs = mutableMapOf<SessionId, Job>()
    private val sessionDrafts = mutableMapOf<SessionId, String>()
    private val sessionDraftRevisions = mutableMapOf<SessionId, Long>()
    private val sessionSendErrors = mutableMapOf<SessionId, MessageSendErrorCategory>()
    private val pendingRunDrafts = mutableMapOf<SessionId, PendingDraft>()
    private val pendingDisconnectedRecoveryEntries = mutableMapOf<String, MutableSet<RunRecoveryEntry>>()
    private val connectionRecoveryJobs = mutableSetOf<Job>()
    private val connectionRecoverySessionCounts = mutableMapOf<SessionId, Int>()
    private val connectionRecoveryFailedSessions = mutableSetOf<SessionId>()
    private val recoverySessionCounts = mutableMapOf<SessionId, Int>()
    private var nextRecoveryClaimId = 0L
    private val recoveryClaims = mutableMapOf<RecoveryEntryKey, RecoveryClaim>()
    private val recoveryHandledEntries = mutableSetOf<RecoveryEntryKey>()
    private var recoveryLoadPending = false
    private var recoveryLoadFailed = false
    private val pendingCreateSessions = mutableMapOf<RecoverySessionKey, PendingCreateState>()
    private val pendingCreateStarted = mutableSetOf<RecoverySessionKey>()

    private val sessionRuns = mutableMapOf<SessionId, List<Run>>()
    private val submittedRunInputs = mutableMapOf<RunId, String>()
    private val unresolvedLocalRunIds = mutableMapOf<RecoverySessionKey, MutableSet<RunId>>()
    private val unresolvedLocalRunAttempts = mutableMapOf<RecoverySessionKey, MutableMap<RunId, String>>()
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
    private val unresolvedSessionMutations = mutableMapOf<RecoverySessionKey, SessionMutationUiState>()
    private val uncertainSendDrafts = mutableMapOf<SessionId, PendingDraft>()
    private val uncertainSubmissionRunIds = mutableMapOf<SessionId, RunId>()
    private val uncertainSubmissionAttemptIds = mutableMapOf<SessionId, String>()
    private val pendingTimedOutSends = mutableMapOf<SessionId, TimedOutSendRecovery>()
    private val reconcilingSessions = mutableMapOf<SessionId, Long>()
    private val notifiedTerminalRunIds = mutableSetOf<RunId>()

    fun onEvent(event: EntryUiEvent) {
        when {
            dispatchConnectionEvent(event) -> Unit
            dispatchSessionNavigationEvent(event) -> Unit
            dispatchSessionMutationEvent(event) -> Unit
            dispatchSessionPinDeleteEvent(event) -> Unit
            dispatchRunEvent(event) -> Unit
            dispatchDiagnosticsEvent(event) -> Unit
        }
    }

    private fun dispatchConnectionEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.AddGatewayConnectionClicked -> handled { showConnectionSetup() }
            event is EntryUiEvent.ChangeGatewayCredentialClicked -> handled { showCredentialRotation() }
            event is EntryUiEvent.CancelGatewayCredentialChangeClicked -> handled { cancelCredentialRotation() }
            event is EntryUiEvent.EndpointChanged -> handled { updateEndpoint(event.value) }
            event is EntryUiEvent.BearerCredentialChanged -> handled { updateBearerCredential(event.value) }
            event is EntryUiEvent.SaveCredentialChanged -> handled { updateSaveCredential(event.value) }
            event is EntryUiEvent.VerifyGatewayConnectionClicked -> handled { verifyConnection() }
            event is EntryUiEvent.TryAgainClicked -> handled { verifyConnection() }
            event is EntryUiEvent.RemoveGatewayConnectionClicked -> handled { removeGatewayConnection() }
            event is EntryUiEvent.RunStatusNotificationsToggleClicked -> handled { toggleRunStatusNotifications() }
            event is EntryUiEvent.RunStatusNotificationPermissionResult ->
                handled { applyRunStatusNotificationPermissionResult(event.granted) }
            else -> false
        }

    private fun dispatchSessionNavigationEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.RefreshSessionsClicked -> handled { refreshSessions() }
            event is EntryUiEvent.RefreshSessionListClicked -> handled { refreshSessionList() }
            event is EntryUiEvent.SessionSearchQueryChanged -> handled { updateSearchQuery(event.value) }
            event is EntryUiEvent.ClearSessionSearchClicked -> handled { clearSearch() }
            event is EntryUiEvent.LoadMoreSessionsClicked -> handled { loadMoreSessions() }
            event is EntryUiEvent.SessionClicked -> handled { openSession(event.sessionId) }
            event is EntryUiEvent.ReturnToSessionListClicked -> handled { returnToSessionList() }
            else -> false
        }

    private fun dispatchSessionMutationEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.CreateSessionClicked -> handled { showCreateSession() }
            event is EntryUiEvent.CreateSessionTitleChanged -> handled { updateCreateSessionTitle(event.value) }
            event is EntryUiEvent.ConfirmCreateSessionClicked -> handled { confirmCreateSession() }
            event is EntryUiEvent.CancelCreateSessionClicked -> handled { cancelCreateSession() }
            event is EntryUiEvent.RenameSessionClicked -> handled { showRenameSession(event.sessionId) }
            event is EntryUiEvent.RenameSessionTitleChanged ->
                handled { updateRenameSessionTitle(event.sessionId, event.value) }
            event is EntryUiEvent.ConfirmRenameSessionClicked -> handled { confirmRenameSession(event.sessionId) }
            event is EntryUiEvent.CancelRenameSessionClicked -> handled { cancelRenameSession(event.sessionId) }
            else -> false
        }

    private fun dispatchSessionPinDeleteEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.PinSessionClicked -> handled { pinSession(event.sessionId) }
            event is EntryUiEvent.UnpinSessionClicked -> handled { unpinSession(event.sessionId) }
            event is EntryUiEvent.DeleteSessionClicked -> handled { showDeleteSession(event.sessionId) }
            event is EntryUiEvent.ConfirmDeleteSessionClicked -> handled { confirmDeleteSession(event.sessionId) }
            event is EntryUiEvent.CancelDeleteSessionClicked -> handled { cancelDeleteSession(event.sessionId) }
            else -> false
        }

    private fun dispatchRunEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.ComposerTextChanged -> handled { updateComposerText(event.value) }
            event is EntryUiEvent.SendMessageClicked -> handled { retryUncertainSubmissionOrSend() }
            event is EntryUiEvent.RetryRunClicked -> handled { retryRun(event.runId) }
            else -> false
        }

    private fun dispatchDiagnosticsEvent(event: EntryUiEvent): Boolean =
        when {
            event is EntryUiEvent.OpenLocalDiagnosticsClicked -> handled { openLocalDiagnostics() }
            event is EntryUiEvent.CloseLocalDiagnosticsClicked -> handled { closeLocalDiagnostics() }
            event is EntryUiEvent.ExportDiagnosticsClicked -> handled { exportLocalDiagnostics() }
            event is EntryUiEvent.ClearDiagnosticsClicked -> handled { requestClearLocalDiagnostics() }
            event is EntryUiEvent.ConfirmClearDiagnosticsClicked -> handled { confirmClearLocalDiagnostics() }
            event is EntryUiEvent.CancelClearDiagnosticsClicked -> handled { cancelClearLocalDiagnostics() }
            else -> false
        }

    fun close() {
        val released = releaseConnectionState()
        released.jobs.forEach(Job::cancel)
        released.observations.forEach(RunEventObservation::close)
        scope.cancel()
    }

    private fun releaseConnectionState(): ReleasedConnectionState =
        synchronized(connectionPersistenceLock) {
            synchronized(sessionRequestLock) {
                sessionRequestGeneration += 1
                connectionGeneration += 1
                sessionGateway = null
                runGateway = null
                val supersededJobs = listOfNotNull(verificationJob, sessionJob, recoveryJob)
                verificationJob = null
                sessionJob = null
                recoveryJob = null
                val requestJobs =
                    listOf(
                        mutationJobs.values,
                        runJobs.values,
                        runObservationJobs.values,
                        connectionRecoveryJobs,
                    ).flatten()
                val observationsToClose = runObservations.values.toList()
                clearSessionStateForClose()
                ReleasedConnectionState(
                    jobs = requestJobs + supersededJobs,
                    observations = observationsToClose,
                )
            }
        }

    private fun clearSessionStateForClose() {
        mutationJobs.clear()
        mutationOwners.clear()
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
        uncertainSubmissionAttemptIds.clear()
        persistPendingCreates()
        pendingCreateSessions.clear()
        pendingCreateStarted.clear()
        unresolvedLocalRunIds.clear()
        unresolvedLocalRunAttempts.clear()
        pendingTimedOutSends.clear()
        reconcilingSessions.clear()
        notifiedTerminalRunIds.clear()
        connectionRecoveryJobs.clear()
        connectionRecoverySessionCounts.clear()
        connectionRecoveryFailedSessions.clear()
        recoverySessionCounts.clear()
        recoveryClaims.clear()
        recoveryHandledEntries.clear()
        recoveryLoadPending = false
        recoveryLoadFailed = false
        sessionDrafts.clear()
        sessionDraftRevisions.clear()
        sessionSendErrors.clear()
        pendingRunDrafts.clear()
        sessionRuns.clear()
        submittedRunInputs.clear()
        authoritativeSessionHistoryGenerations.clear()
        authoritativeSessionRuns.clear()
    }

    private fun persistPendingCreates() {
        pendingCreateSessions.forEach { (key, pendingCreate) ->
            val submissionKey = PendingRunSubmissionKey(key.endpoint, key.sessionId)
            val persisted =
                runCatching {
                    runSubmissionUncertaintyStore.add(
                        submissionKey,
                        pendingCreate.knownRunIds,
                        pendingCreate.attemptId,
                    )
                }.getOrDefault(false)
            if (persisted && key !in pendingCreateStarted) {
                runCatching {
                    runSubmissionUncertaintyStore.markSettled(submissionKey, pendingCreate.attemptId)
                }
            }
        }
    }

    private fun showConnectionSetup() {
        if (_uiState.value.isConnected) return
        _uiState.value = _uiState.value.connectionSetupState()
    }

    private fun showCredentialRotation() {
        val state = _uiState.value
        if (!state.isConnected || state.isVerifying) return
        _uiState.value =
            state.copy(
                title = "Change Gateway credential",
                supportingText = "Verify the replacement before it replaces the saved credential.",
                actionLabel = "Save credential",
                connectionSetupRequested = true,
                isChangingCredential = true,
                bearerCredential = "",
                errorCategory = null,
            )
    }

    private fun cancelCredentialRotation() {
        val state = _uiState.value
        if (!state.isChangingCredential || state.isVerifying) return
        _uiState.value =
            state.copy(
                title = "Gateway connected",
                supportingText = "The Gateway contract was verified successfully.",
                actionLabel = "Connected",
                isChangingCredential = false,
                errorCategory = null,
            )
    }

    private fun updateEndpoint(value: String) {
        val state = _uiState.value
        if (state.isVerifying || state.isConnected) return
        _uiState.value = state.copy(endpoint = value, errorCategory = null)
    }

    private fun updateBearerCredential(value: String) {
        val state = _uiState.value
        if (state.isVerifying || (state.isConnected && !state.isChangingCredential)) return
        _uiState.value = state.copy(bearerCredential = value, errorCategory = null)
    }

    private fun updateSaveCredential(value: Boolean) {
        val state = _uiState.value
        if (state.isVerifying || (state.isConnected && !state.isChangingCredential)) return
        _uiState.value = state.copy(saveCredential = value, errorCategory = null)
    }

    private fun verifyConnection() {
        val verifier = verifyGatewayConnection ?: return
        val job =
            synchronized(connectionPersistenceLock) {
                synchronized(sessionRequestLock) {
                    val state = _uiState.value
                    if (state.blocksVerification()) {
                        null
                    } else {
                        verificationJob?.cancel()
                        val requestConnectionGeneration = connectionGeneration
                        _uiState.value = state.copy(isVerifying = true, errorCategory = null)
                        val verification =
                            scope.launch(start = CoroutineStart.LAZY) {
                                runVerification(verifier, state, requestConnectionGeneration)
                            }
                        verificationJob = verification
                        verification
                    }
                }
            } ?: return
        job.start()
    }

    private fun EntryUiState.blocksVerification(): Boolean =
        !connectionSetupRequested || isVerifying || (isConnected && !isChangingCredential)

    private fun runVerification(
        verifier: VerifyGatewayConnection,
        state: EntryUiState,
        requestConnectionGeneration: Long,
    ) {
        try {
            verifier.executeWithoutPersistence(
                endpoint = state.endpoint,
                bearerCredential = state.bearerCredential,
            )
            val normalizedEndpoint = normalizeGatewayEndpoint(state.endpoint)
            if (!persistVerifiedCredential(verifier, state, requestConnectionGeneration, normalizedEndpoint)) return
            val gateway = connectVerifiedGateway(state, normalizedEndpoint, requestConnectionGeneration)
            recordDiagnostic(
                LocalDiagnosticEventType.GATEWAY_CONNECTION_VERIFICATION,
                LocalDiagnosticStatus.SUCCEEDED,
            )
            flushPendingRecoveryEntries(normalizedEndpoint)
            startRunRecovery(requestConnectionGeneration)
            gateway?.let(::loadInitialSessions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayException) {
            showFailure(error.category.toUserFacingCategory(), requestConnectionGeneration)
        } catch (_: GatewayConnectionPersistenceException) {
            showFailure(
                EntryErrorCategory.CREDENTIAL_STORAGE_FAILED,
                requestConnectionGeneration,
            )
        } catch (_: Exception) {
            showFailure(EntryErrorCategory.GATEWAY_REQUEST_FAILED, requestConnectionGeneration)
        }
    }

    private fun persistVerifiedCredential(
        verifier: VerifyGatewayConnection,
        state: EntryUiState,
        requestConnectionGeneration: Long,
        normalizedEndpoint: String,
    ): Boolean {
        val canPersist =
            synchronized(sessionRequestLock) {
                connectionGeneration == requestConnectionGeneration
            }
        if (!canPersist) return false
        return synchronized(connectionPersistenceLock) {
            val stillCurrent =
                synchronized(sessionRequestLock) {
                    connectionGeneration == requestConnectionGeneration
                }
            if (!stillCurrent) {
                false
            } else {
                verifier.persist(
                    endpoint = normalizedEndpoint,
                    bearerCredential = state.bearerCredential,
                    saveCredential = state.saveCredential,
                )
                true
            }
        }
    }

    private fun connectVerifiedGateway(
        state: EntryUiState,
        normalizedEndpoint: String,
        requestConnectionGeneration: Long,
    ): SessionGatewayPort? =
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
                        supportingText =
                            "The Gateway contract was verified successfully.",
                        actionLabel = "Connected",
                        isChangingCredential = false,
                        isVerifying = false,
                        isConnected = true,
                        isGatewayConnectionConfigured = true,
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

    private fun showFailure(
        category: EntryErrorCategory,
        requestConnectionGeneration: Long,
    ) {
        recordDiagnostic(
            LocalDiagnosticEventType.GATEWAY_CONNECTION_VERIFICATION,
            LocalDiagnosticStatus.FAILED,
        )
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
        val released = releaseGatewayConnection()
        released.jobs.forEach(Job::cancel)
        released.observations.forEach(RunEventObservation::close)
    }

    private fun releaseGatewayConnection(): ReleasedConnectionState =
        synchronized(connectionPersistenceLock) {
            val released = releaseGatewayConnectionLocked()
            removeGatewayConnectionUseCase?.execute()
            updateRunRecoveryEndpoint?.invoke(null)
            _uiState.value =
                EntryState()
                    .toUiState()
                    .copy(runStatusNotifications = restoredRunStatusNotificationState())
            released
        }

    private fun releaseGatewayConnectionLocked(): ReleasedConnectionState =
        synchronized(sessionRequestLock) {
            rememberUnresolvedSessionMutations(_uiState.value.endpoint)
            sessionRequestGeneration += 1
            connectionGeneration += 1
            val supersededJobs = listOfNotNull(verificationJob, sessionJob, recoveryJob)
            verificationJob = null
            sessionJob = null
            recoveryJob = null
            persistPendingCreates()
            pendingCreateStarted.clear()
            pendingCreateSessions.clear()
            sessionGateway = null
            runGateway = null
            mutationOwners.clear()
            val connectionRecoveryJobsToCancel = connectionRecoveryJobs.toList()
            clearRecoveryState()
            clearSessionDraftsAndRuns()
            val observationsToClose = runObservations.values.toList()
            val requestJobs = collectAndClearRequestJobs()
            ReleasedConnectionState(
                jobs = requestJobs + supersededJobs + connectionRecoveryJobsToCancel,
                observations = observationsToClose,
            )
        }

    private fun clearRecoveryState() {
        connectionRecoveryJobs.clear()
        connectionRecoverySessionCounts.clear()
        connectionRecoveryFailedSessions.clear()
        recoverySessionCounts.clear()
        recoveryClaims.clear()
        recoveryHandledEntries.clear()
    }

    private fun clearSessionDraftsAndRuns() {
        sessionDrafts.clear()
        sessionDraftRevisions.clear()
        sessionSendErrors.clear()
        pendingRunDrafts.clear()
        sessionRuns.clear()
        submittedRunInputs.clear()
        authoritativeSessionHistoryGenerations.clear()
        authoritativeSessionRuns.clear()
    }

    private fun collectAndClearRequestJobs(): List<Job> =
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
            unresolvedLocalRunIds.clear()
            unresolvedLocalRunAttempts.clear()
            uncertainSendDrafts.clear()
            uncertainSubmissionRunIds.clear()
            uncertainSubmissionAttemptIds.clear()
            pendingTimedOutSends.clear()
            reconcilingSessions.clear()
            notifiedTerminalRunIds.clear()
        }

    private fun deniedRunStatusNotificationsUiState(): RunStatusNotificationsUiState =
        RunStatusNotificationsUiState(
            enabled = false,
            explanation = RunStatusNotificationExplanation.PERMISSION_DENIED,
        )

    private fun restoredRunStatusNotificationState(): RunStatusNotificationsUiState {
        val store = runStatusNotificationSettingsStore ?: return RunStatusNotificationsUiState()
        return when {
            !store.loadEnabled() -> RunStatusNotificationsUiState()
            runStatusNotificationPermission?.canPost() == true ->
                RunStatusNotificationsUiState(enabled = true)
            else -> {
                store.saveEnabled(false)
                deniedRunStatusNotificationsUiState()
            }
        }
    }

    private fun toggleRunStatusNotifications() {
        val store = runStatusNotificationSettingsStore ?: return
        val current = _uiState.value.runStatusNotifications
        if (current.enabled) {
            store.saveEnabled(false)
            _uiState.value =
                _uiState.value.copy(runStatusNotifications = RunStatusNotificationsUiState())
        } else if (runStatusNotificationPermission?.requiresRuntimePermissionRequest() == true) {
            requestRunStatusNotificationPermission?.invoke()
        } else if (runStatusNotificationPermission?.canPost() == false) {
            _uiState.value =
                _uiState.value.copy(
                    runStatusNotifications = deniedRunStatusNotificationsUiState(),
                )
        } else {
            store.saveEnabled(true)
            _uiState.value =
                _uiState.value.copy(
                    runStatusNotifications = RunStatusNotificationsUiState(enabled = true),
                )
        }
    }

    private fun applyRunStatusNotificationPermissionResult(granted: Boolean) {
        val store = runStatusNotificationSettingsStore ?: return
        if (granted) {
            store.saveEnabled(true)
            _uiState.value =
                _uiState.value.copy(
                    runStatusNotifications = RunStatusNotificationsUiState(enabled = true),
                )
        } else {
            store.saveEnabled(false)
            _uiState.value =
                _uiState.value.copy(
                    runStatusNotifications = deniedRunStatusNotificationsUiState(),
                )
        }
    }

    private fun openLocalDiagnostics() {
        _uiState.update { state ->
            state.copy(
                localDiagnostics = LocalDiagnosticsUiState(isOpen = true, isLoadingRecords = true),
            )
        }
        scope.launch { applyLocalDiagnosticsRecordCount() }
    }

    private fun closeLocalDiagnostics() {
        _uiState.update { state -> state.copy(localDiagnostics = LocalDiagnosticsUiState()) }
    }

    private fun exportLocalDiagnostics() {
        val exporter = localDiagnostics?.exporter ?: return
        val current = _uiState.value.localDiagnostics
        if (!current.isOpen || !current.isExportEnabled) return
        _uiState.update { state ->
            state.copy(
                localDiagnostics =
                    state.localDiagnostics.copy(isExporting = true, exportFailure = null),
            )
        }
        scope.launch {
            val result =
                runCatching { exporter.export() }
                    .getOrDefault(LocalDiagnosticsExportResult.FAILED)
            _uiState.update { state ->
                state.copy(
                    localDiagnostics =
                        state.localDiagnostics.copy(
                            isExporting = false,
                            exportFailure =
                                if (result == LocalDiagnosticsExportResult.FAILED) {
                                    LocalDiagnosticsExportFailure.EXPORT_FAILED
                                } else {
                                    null
                                },
                        ),
                )
            }
            applyLocalDiagnosticsRecordCount()
        }
    }

    private fun requestClearLocalDiagnostics() {
        val current = _uiState.value.localDiagnostics
        if (!current.isOpen || !current.hasRecords) return
        _uiState.update { state ->
            state.copy(
                localDiagnostics = state.localDiagnostics.copy(isClearConfirmationOpen = true),
            )
        }
    }

    private fun cancelClearLocalDiagnostics() {
        _uiState.update { state ->
            state.copy(
                localDiagnostics = state.localDiagnostics.copy(isClearConfirmationOpen = false),
            )
        }
    }

    private fun confirmClearLocalDiagnostics() {
        val store = localDiagnostics?.store ?: return
        val current = _uiState.value.localDiagnostics
        val canClear =
            current.isOpen &&
                current.isClearConfirmationOpen &&
                localDiagnosticsClearInFlight.compareAndSet(false, true)
        if (!canClear) return
        scope.launch {
            try {
                val cleared = runCatching { store.clear() }.isSuccess
                _uiState.update { state ->
                    if (!state.localDiagnostics.isOpen) {
                        state
                    } else {
                        state.copy(
                            localDiagnostics =
                                if (cleared) {
                                    state.localDiagnostics.copy(isClearConfirmationOpen = false, recordCount = 0)
                                } else {
                                    state.localDiagnostics
                                },
                        )
                    }
                }
            } finally {
                localDiagnosticsClearInFlight.set(false)
            }
        }
    }

    private fun localDiagnosticsRecordCount(): Int =
        localDiagnostics
            ?.let { ports -> runCatching { ports.store.recordCount() }.getOrNull() }
            ?: 0

    private fun applyLocalDiagnosticsRecordCount() {
        val recordCount = localDiagnosticsRecordCount()
        _uiState.update { state ->
            if (!state.localDiagnostics.isOpen) {
                state
            } else {
                state.copy(
                    localDiagnostics =
                        state.localDiagnostics.copy(isLoadingRecords = false, recordCount = recordCount),
                )
            }
        }
    }

    private fun recordDiagnostic(
        eventType: LocalDiagnosticEventType,
        status: LocalDiagnosticStatus? = null,
    ) {
        localDiagnostics?.let { ports ->
            runCatching { ports.recorder.record(LocalDiagnosticEvent(eventType, status)) }
        }
        if (!_uiState.value.localDiagnostics.isOpen) return
        scope.launch { applyLocalDiagnosticsRecordCount() }
    }

    private fun postTerminalRunStatusNotificationOnce(
        run: Run,
        state: RunPresentationState,
    ) {
        if (
            state.isNotifiableTerminal() &&
            notifiedTerminalRunIds.add(run.id)
        ) {
            maybePostTerminalRunStatusNotification(run, state)
        }
    }

    private fun maybePostTerminalRunStatusNotification(
        run: Run,
        state: RunPresentationState,
    ) {
        val notifier = runStatusNotifier ?: return
        val enabled = runStatusNotificationSettingsStore?.loadEnabled() == true
        val canPost = runStatusNotificationPermission?.canPost() ?: true
        if (!shouldPostRunStatusNotification(enabled, canPost, state)) return
        runCatching { notifier.postTerminal(run, state) }
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
                    ?: error("No endpoint-scoped recovery writer is configured.")
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

    private fun beginRecoveryLoad(expectedConnectionGeneration: Long): Boolean =
        synchronized(sessionRequestLock) {
            if (connectionGeneration != expectedConnectionGeneration) {
                false
            } else {
                recoveryLoadPending = true
                recoveryLoadFailed = false
                val current = _uiState.value.sessionList
                val opened = current?.openedSession
                if (current != null && opened != null) {
                    _uiState.value =
                        _uiState.value.copy(
                            sessionList =
                                current.copy(
                                    openedSession =
                                        opened.copy(
                                            isRefreshing = true,
                                            isReconciliationInProgress = true,
                                            hasUnresolvedSubmission = true,
                                        ),
                                ),
                        )
                }
                true
            }
        }

    private fun finishRecoveryLoad(
        expectedConnectionGeneration: Long,
        entries: List<RunRecoveryEntry>,
        failed: Boolean,
    ) {
        synchronized(sessionRequestLock) {
            if (connectionGeneration != expectedConnectionGeneration) return
            recoveryLoadPending = false
            recoveryLoadFailed = failed
            val recoveredSessionIds = entries.mapTo(mutableSetOf()) { it.sessionId }
            val current = _uiState.value.sessionList
            if (failed) {
                current?.sessions?.forEach { item -> recoveryUnavailableSessions += item.id }
                current?.openedSession?.session?.id?.let(recoveryUnavailableSessions::add)
            }
            val opened = current?.openedSession
            if (current != null && opened != null) {
                val sessionId = opened.session.id
                val recoveryInProgress =
                    sessionId in recoveredSessionIds ||
                        sessionId in connectionRecoverySessionCounts ||
                        sessionId in recoverySessionCounts ||
                        sessionId in reconcilingSessions
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        isRefreshing = if (failed) false else recoveryInProgress,
                                        isReconciliationInProgress = if (failed) false else recoveryInProgress,
                                        hasUnresolvedSubmission =
                                            failed || hasUnresolvedSubmission(sessionId),
                                        sendErrorCategory =
                                            if (failed) {
                                                MessageSendErrorCategory.UNCERTAIN
                                            } else {
                                                sendErrorCategoryFor(sessionId)
                                            },
                                    ),
                            ),
                    )
            }
        }
    }

    private fun markRecoveryFailure(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            recoveryUnavailableSessions += sessionId
            val current = _uiState.value.sessionList
            val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
            if (current != null && opened != null) {
                _uiState.value =
                    _uiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        isRefreshing = false,
                                        isReconciliationInProgress = false,
                                        hasUnresolvedSubmission = true,
                                        sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                    ),
                            ),
                    )
            }
        }
    }

    private fun hasPendingRecoveryWork(sessionId: SessionId): Boolean =
        sessionId in connectionRecoverySessionCounts ||
            sessionId in recoverySessionCounts ||
            sessionId in reconcilingSessions ||
            recoveryLoadPending

    private fun openedSessionFor(sessionId: SessionId): Pair<SessionListUiState, OpenSessionUiState>? =
        _uiState.value.sessionList?.let { current ->
            current.openedSession?.takeIf { it.session.id == sessionId }?.let { opened -> current to opened }
        }

    private fun clearRecoveryUiIfIdle(sessionId: SessionId) {
        if (hasPendingRecoveryWork(sessionId)) return
        val (current, opened) = openedSessionFor(sessionId) ?: return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isRefreshing = false,
                                isReconciliationInProgress = false,
                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                sendErrorCategory = sendErrorCategoryFor(sessionId),
                            ),
                    ),
            )
    }

    private fun claimRecoveryEntry(
        endpoint: String,
        entry: RunRecoveryEntry,
        expectedConnectionGeneration: Long,
    ): RecoveryClaim? {
        val key = RecoveryEntryKey(endpoint, entry.sessionId, entry.runId)
        val canClaim =
            connectionGeneration == expectedConnectionGeneration &&
                key !in recoveryHandledEntries &&
                key !in recoveryClaims
        if (!canClaim) return null
        return RecoveryClaim(
            key = key,
            connectionGeneration = expectedConnectionGeneration,
            claimId = ++nextRecoveryClaimId,
        ).also { claim -> recoveryClaims[key] = claim }
    }

    private fun releaseRecoveryClaim(claim: RecoveryClaim): Boolean {
        if (recoveryClaims[claim.key] != claim) return false
        recoveryClaims.remove(claim.key)
        return true
    }

    private fun startRunRecovery(expectedConnectionGeneration: Long) {
        val recovery = beginRunRecovery(expectedConnectionGeneration) ?: return
        val (registry, recoveryContext) = recovery
        if (!beginRecoveryLoad(expectedConnectionGeneration)) return
        val entries = loadRecoveryEntries(registry)
        when {
            entries == null ->
                finishRecoveryLoad(expectedConnectionGeneration, emptyList(), failed = true)
            entries.isEmpty() ->
                finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
            else -> {
                val workItems = claimRecoveryWorkItems(entries, recoveryContext, expectedConnectionGeneration)
                when {
                    workItems == null ->
                        finishRecoveryLoad(expectedConnectionGeneration, emptyList(), failed = true)
                    workItems.isEmpty() ->
                        finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
                    else ->
                        startRecoveryWork(workItems, recoveryContext, expectedConnectionGeneration, entries)
                }
            }
        }
    }

    private fun beginRunRecovery(expectedConnectionGeneration: Long): RunRecoveryStart? {
        return runRecoveryRegistry?.let { registry ->
            recoveryGatewayContextFor(expectedConnectionGeneration)?.let { context -> registry to context }
        }
    }

    private fun recoveryGatewayContextFor(expectedConnectionGeneration: Long): RecoveryGatewayContext? =
        synchronized(sessionRequestLock) {
            if (connectionGeneration != expectedConnectionGeneration) {
                null
            } else {
                val sessionGateway = sessionGateway
                val runGateway = runGateway
                if (sessionGateway == null || runGateway == null) {
                    null
                } else {
                    RecoveryGatewayContext(
                        endpoint = _uiState.value.endpoint,
                        sessionGateway = sessionGateway,
                        runGateway = runGateway,
                    )
                }
            }
        }

    private fun loadRecoveryEntries(registry: RunRecoveryRegistry): List<RunRecoveryEntry>? =
        try {
            registry.load().filter(RunRecoveryEntry::isValid)
        } catch (_: Exception) {
            null
        }

    private fun claimRecoveryWorkItems(
        entries: List<RunRecoveryEntry>,
        recoveryContext: RecoveryGatewayContext,
        expectedConnectionGeneration: Long,
    ): List<RecoveryWorkItem>? =
        synchronized(sessionRequestLock) {
            if (connectionGeneration != expectedConnectionGeneration) {
                null
            } else {
                entries.mapNotNull { entry ->
                    val claim =
                        claimRecoveryEntry(recoveryContext.endpoint, entry, expectedConnectionGeneration)
                            ?: return@mapNotNull null
                    rememberRecoveredRun(
                        entry = entry,
                        run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                        updateVisibleState = false,
                    )
                    recoverySessionCounts[entry.sessionId] =
                        (recoverySessionCounts[entry.sessionId] ?: 0) + 1
                    RecoveryWorkItem(entry, claim)
                }
            }
        }

    private fun startRecoveryWork(
        workItems: List<RecoveryWorkItem>,
        recoveryContext: RecoveryGatewayContext,
        expectedConnectionGeneration: Long,
        entries: List<RunRecoveryEntry>,
    ) {
        lateinit var job: Job
        var jobToCancel: Job? = null
        synchronized(sessionRequestLock) {
            jobToCancel = recoveryJob
            job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        workItems.forEach { workItem ->
                            runRecoveryWorkItem(workItem, recoveryContext, expectedConnectionGeneration)
                        }
                    } finally {
                        releaseRecoveryWork(workItems, expectedConnectionGeneration, job)
                    }
                }
            recoveryJob = job
        }
        finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
        jobToCancel?.cancel()
        job.start()
    }

    private suspend fun runRecoveryWorkItem(
        workItem: RecoveryWorkItem,
        recoveryContext: RecoveryGatewayContext,
        expectedConnectionGeneration: Long,
    ) {
        val entry = workItem.entry
        currentCoroutineContext().ensureActive()
        val expectedHistoryGeneration =
            synchronized(sessionRequestLock) {
                authoritativeSessionHistoryGenerations[entry.sessionId] ?: 0L
            }
        val recoveredRun =
            recoverRun(
                entry = entry,
                context =
                    RecoveryRunContext(
                        sessionGateway = recoveryContext.sessionGateway,
                        runGateway = recoveryContext.runGateway,
                        connectionGeneration = expectedConnectionGeneration,
                        historyGeneration = expectedHistoryGeneration,
                    ),
            )
        synchronized(sessionRequestLock) {
            if (connectionGeneration == expectedConnectionGeneration) {
                recoveryHandledEntries +=
                    RecoveryEntryKey(recoveryContext.endpoint, entry.sessionId, entry.runId)
            }
        }
        if (recoveredRun == null) {
            markRecoveryFailure(entry.sessionId)
        } else {
            recoveredRun.takeIf(Run::isActive)?.let { run ->
                startRunObservation(
                    sessionId = entry.sessionId,
                    run = run,
                    expectedConnectionGeneration = expectedConnectionGeneration,
                    expectedHistoryGeneration = expectedHistoryGeneration,
                )
            }
        }
    }

    private fun releaseRecoveryWork(
        workItems: List<RecoveryWorkItem>,
        expectedConnectionGeneration: Long,
        job: Job,
    ) {
        synchronized(sessionRequestLock) {
            if (connectionGeneration == expectedConnectionGeneration) {
                workItems.forEach { workItem ->
                    if (releaseRecoveryClaim(workItem.claim)) {
                        val entry = workItem.entry
                        val remaining = (recoverySessionCounts[entry.sessionId] ?: 1) - 1
                        if (remaining > 0) {
                            recoverySessionCounts[entry.sessionId] = remaining
                        } else {
                            recoverySessionCounts.remove(entry.sessionId)
                        }
                        clearRecoveryUiIfIdle(entry.sessionId)
                    }
                }
            }
            if (recoveryJob === job) recoveryJob = null
        }
    }

    private suspend fun recoverRun(
        entry: RunRecoveryEntry,
        context: RecoveryRunContext,
    ): Run? {
        val reconciliation =
            try {
                runInterruptible {
                    ReconcileRun(context.runGateway, context.sessionGateway).execute(entry.runId, entry.sessionId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return null
            }
        applyAuthoritativeRunReconciliation(
            request =
                AuthoritativeReconciliationRequest(
                    sessionId = entry.sessionId,
                    connectionGeneration = context.connectionGeneration,
                    sessionGeneration = context.sessionGeneration,
                    historyGeneration = context.historyGeneration,
                    updateVisibleUi = context.updateVisibleUi,
                ),
            reconciliation = reconciliation,
        )
        return reconciliation.run
    }

    private fun rememberRecoveredRun(
        entry: RunRecoveryEntry,
        run: Run,
        updateVisibleState: Boolean = true,
    ) {
        val pendingKey = PendingRunSubmissionKey(_uiState.value.endpoint, entry.sessionId)
        val uncertaintySnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
        if (uncertaintySnapshot != null && entry.runId !in uncertaintySnapshot.knownRunIds) {
            val currentAttemptId = uncertainSubmissionAttemptIds[entry.sessionId] ?: uncertaintySnapshot.attemptId
            if (
                uncertaintySnapshot.attemptId == currentAttemptId &&
                uncertaintySnapshot.boundRunId == entry.runId
            ) {
                uncertainSubmissionRunIds[entry.sessionId] = entry.runId
                uncertainSubmissionAttemptIds[entry.sessionId] = uncertaintySnapshot.attemptId
            } else {
                if (uncertainSubmissionRunIds[entry.sessionId] == entry.runId) {
                    uncertainSubmissionRunIds.remove(entry.sessionId)
                    uncertainSubmissionAttemptIds.remove(entry.sessionId)
                }
                unresolvedSubmissionSessions += entry.sessionId
                ambiguousSubmissionSessions += entry.sessionId
                runCatching {
                    runSubmissionUncertaintyStore.markAmbiguous(
                        pendingKey,
                        uncertaintySnapshot.attemptId,
                    )
                }
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
                                latestRunState =
                                    latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                            ),
                    ),
            )
    }

    private fun updateSearchQuery(value: String) {
        var observationToClose: RunEventObservation? = null
        var observationJobToCancel: Job? = null
        synchronized(sessionRequestLock) {
            val state = _uiState.value
            val sessionList = state.sessionList ?: return
            val searchIsBlocked =
                sessionList.searchQuery == value ||
                    sessionList.createSession?.isSubmitting == true ||
                    sessionList.sessionMutations.isNotEmpty()
            if (searchIsBlocked) return

            sessionList.openedSession?.session?.id?.let { openedSessionId ->
                val released = releaseRunObservation(openedSessionId)
                observationJobToCancel = released.job
                observationToClose = released.observation
            }

            _uiState.value =
                state.copy(
                    sessionList =
                        sessionList.copy(
                            searchQuery = value,
                            openingSessionId = null,
                            openedSession = null,
                            errorCategory = null,
                        ),
                )
        }
        observationJobToCancel?.cancel()
        observationToClose?.close()
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
                } else {
                    startFirstPageListLoad(gateway, sessionList)
                }
            }
        job?.start()
    }

    private fun refreshSessionList() {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val sessionList = _uiState.value.sessionList ?: return@synchronized null
                startFirstPageListLoad(gateway, sessionList)
            }
        job?.start()
    }

    private fun startFirstPageListLoad(
        gateway: SessionGatewayPort,
        sessionList: SessionListUiState,
    ): Job? =
        if (sessionList.blocksFirstPageLoad()) {
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

    private fun SessionListUiState.blocksFirstPageLoad(): Boolean =
        (isLoading && !isSearching) ||
            isRefreshing ||
            openingSessionId != null ||
            createSession != null ||
            hasPendingMutation ||
            openedSession?.isRefreshing == true ||
            openedSession?.isReconciliationInProgress == true

    private fun SessionListUiState.blocksLoadingMore(): Boolean =
        isLoading ||
            isRefreshing ||
            isSearching ||
            isLoadingMore ||
            isUnavailable ||
            openedSession?.isRefreshing == true ||
            openedSession?.isReconciliationInProgress == true ||
            openingSessionId != null ||
            createSession != null ||
            sessionMutations.isNotEmpty()

    private fun SessionListUiState.blocksCreateSession(): Boolean =
        isLoading ||
            isRefreshing ||
            isLoadingMore ||
            openingSessionId != null ||
            isUnavailable ||
            createSession != null ||
            sessionMutations.isNotEmpty()

    private fun SessionListUiState.blocksSessionOpen(): Boolean =
        isLoading ||
            isRefreshing ||
            isSearching ||
            isUnavailable ||
            isLoadingMore ||
            hasPendingMutation ||
            openingSessionId != null ||
            createSession != null

    private fun blocksRunSubmission(
        opened: OpenSessionUiState,
        sessionId: SessionId,
    ): Boolean =
        opened.latestRunState == RunPresentationState.UNCERTAIN ||
            opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN ||
            opened.hasUnresolvedSubmission ||
            opened.isRefreshing ||
            opened.isReconciliationInProgress ||
            sessionId in connectionRecoverySessionCounts ||
            sessionId in recoverySessionCounts ||
            recoveryLoadPending ||
            recoveryLoadFailed

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
                    ?: latestObservedObservationState(
                        openedSession.session.id,
                        visibleSessionRuns(openedSession.session.id),
                    )?.run?.id
            val requestGeneration = beginSessionRequest()
            val requestConnectionGeneration = connectionGeneration
            val request = SessionRequestContext(requestGeneration, sessionList.searchQuery, offset = null)
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
                updateCurrentSessionRequest(request.generation) { current ->
                    applyRefreshedSessionResult(current, openedSession, sessionId)
                }
            if (applied) {
                continueRefreshedSessionRecovery(
                    gateway = gateway,
                    request = request,
                    requestConnectionGeneration = requestConnectionGeneration,
                    openedSession = openedSession,
                    runIdToReconcile = runIdToReconcile,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showOpenedSessionFailure(request.generation)
        } catch (_: Exception) {
            showOpenedSessionFailure(request.generation)
        }
    }

    private fun applyRefreshedSessionResult(
        current: SessionListUiState,
        openedSession: OpenedSession,
        sessionId: SessionId,
    ): SessionListUiState {
        val previous = current.openedSession ?: return current
        val authoritativeSession = openedSession.session.toSessionItemUiState()
        val knownRuns = rememberSessionRuns(sessionId, openedSession)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        val latestRun = knownRuns.latestRun()
        return current.copy(
            sessions =
                current.sessions
                    .map { item -> if (item.id == sessionId) authoritativeSession else item }
                    .orderedSessions(),
            openedSession =
                openedSession.toOpenSessionUiState(
                    OpenSessionOverlay(
                        messages = openedSession.history.toMessageUiStates(),
                        composerText = sessionDrafts[sessionId] ?: previous.composerText,
                        sendErrorCategory = sendErrorCategoryFor(sessionId),
                        hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                        latestRun = latestRun,
                        activeRuns = knownRuns.activeRuns(),
                        isSending = previous.isSending || runJobs.containsKey(sessionId),
                        latestRunState =
                            latestObservation?.state ?: latestRun?.toRunPresentationState(),
                        latestRunRetryAvailable =
                            latestRunRetryAvailable(
                                knownRuns,
                                latestObservation?.state ?: latestRun?.toRunPresentationState(),
                            ),
                        activeResponse = observedMessageUiState(latestObservation),
                        isRefreshing = previous.isRefreshing,
                    ),
                ).copy(
                    isReconciliationInProgress =
                        previous.isReconciliationInProgress ||
                            openedSession.history.latestRun() != null,
                ),
            errorCategory = null,
        )
    }

    private suspend fun continueRefreshedSessionRecovery(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        requestConnectionGeneration: Long,
        openedSession: OpenedSession,
        runIdToReconcile: RunId?,
    ) {
        val sessionId = openedSession.session.id
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
                request = ReconciliationRequest(requestConnectionGeneration, request.generation),
                knownRunIds = pendingTimeoutRecovery.knownRunIds,
            )
        } else if (pendingSettledRecovery != null) {
            reconcileTimedOutSendNow(
                sessionId = sessionId,
                request = ReconciliationRequest(requestConnectionGeneration, request.generation),
                knownRunIds = pendingSettledRecovery.knownRunIds,
                resolveWhenNoNewRun = true,
            )
        } else {
            reconcileOpenedRun(
                OpenedRunReconcileContext(
                    sessionId = sessionId,
                    requestSessionGeneration = request.generation,
                    requestConnectionGeneration = requestConnectionGeneration,
                    sessionGateway = gateway,
                    runIdToReconcile = runIdToReconcile ?: historyRunIdToReconcile(sessionId, openedSession),
                    restartObservation = true,
                    clearRefreshWhenNoRun = true,
                ),
            )
        }
    }

    private fun reconcileOpenedRun(context: OpenedRunReconcileContext) {
        val recoveryEntries =
            try {
                runRecoveryRegistry?.load().orEmpty()
            } catch (_: Exception) {
                null
            }
        val recoveryEntriesLoadFailed = recoveryEntries == null
        val sessionRecoveryEntries =
            recoveryEntries.orEmpty().filter { entry -> entry.sessionId == context.sessionId }
        val runIds = reconciliationRunIds(context, sessionRecoveryEntries, recoveryEntriesLoadFailed) ?: return
        if (runIds.isEmpty()) {
            if (context.clearRefreshWhenNoRun) {
                finishReconciliation(
                    context.sessionId,
                    context.requestConnectionGeneration,
                    context.requestSessionGeneration,
                )
            }
            if (recoveryLoadFailed) {
                markRecoveryUnavailable(
                    context.sessionId,
                    context.requestConnectionGeneration,
                    context.requestSessionGeneration,
                )
            }
            return
        }
        var activeRunToObserve: Run? = null
        runIds.forEach { runId ->
            reconcileRunObservationCandidate(context, runId, activeRunToObserve != null)
                ?.let { candidate -> activeRunToObserve = candidate }
        }
        val runToObserve = activeRunToObserve
        if (
            context.restartObservation &&
            runToObserve != null &&
            shouldReconcileCurrentSession(
                context.requestConnectionGeneration,
                context.requestSessionGeneration,
                context.sessionId,
            )
        ) {
            startRunObservation(
                sessionId = context.sessionId,
                run = runToObserve,
                expectedConnectionGeneration = context.requestConnectionGeneration,
                expectedSessionGeneration = context.requestSessionGeneration,
            )
        }
        if (recoveryLoadFailed) {
            markRecoveryUnavailable(
                context.sessionId,
                context.requestConnectionGeneration,
                context.requestSessionGeneration,
            )
        }
    }

    private fun reconciliationRunIds(
        context: OpenedRunReconcileContext,
        sessionRecoveryEntries: List<RunRecoveryEntry>,
        recoveryEntriesLoadFailed: Boolean,
    ): List<RunId>? =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != context.requestConnectionGeneration ||
                sessionRequestGeneration != context.requestSessionGeneration ||
                this@EntryStateHolder.sessionGateway !== context.sessionGateway
            ) {
                null
            } else {
                val ids = linkedSetOf<RunId>()
                context.runIdToReconcile?.let(ids::add)
                val pendingKey = PendingRunSubmissionKey(_uiState.value.endpoint, context.sessionId)
                runSubmissionUncertaintyStore
                    .boundRunId(pendingKey)
                    ?.let { boundRunId ->
                        uncertainSubmissionRunIds[context.sessionId] = boundRunId
                        uncertainSubmissionAttemptIds[context.sessionId] =
                            runSubmissionUncertaintyStore.attemptId(pendingKey) ?: LEGACY_ATTEMPT_ID
                    }
                recoveryLoadPending = false
                recoveryLoadFailed = recoveryEntriesLoadFailed
                if (recoveryEntriesLoadFailed) {
                    recoveryUnavailableSessions.add(context.sessionId)
                } else {
                    recoveryUnavailableSessions.remove(context.sessionId)
                }
                sessionRecoveryEntries.forEach { entry ->
                    ids += entry.runId
                    if (
                        sessionRuns[context.sessionId].orEmpty().none { it.id == entry.runId } &&
                        observationStateFor(context.sessionId, entry.runId) == null
                    ) {
                        rememberRecoveredRun(
                            entry = entry,
                            run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                            updateVisibleState = false,
                        )
                    }
                }
                sessionRuns[context.sessionId].orEmpty().filter(Run::isActive).mapTo(ids) { it.id }
                unresolvedLocalRunIds[recoverySessionKey(context.sessionId)].orEmpty().forEach(ids::add)
                uncertainSubmissionRunIds[context.sessionId]?.let(ids::add)
                visibleSessionRuns(context.sessionId).latestActiveRun()?.id?.let(ids::add)
                if (ids.isEmpty()) {
                    latestObservedObservationState(context.sessionId, visibleSessionRuns(context.sessionId))
                        ?.takeIf { state -> !state.state.isTerminal() }
                        ?.run
                        ?.id
                        ?.let(ids::add)
                }
                ids.toList()
            }
        }

    private fun reconcileRunObservationCandidate(
        context: OpenedRunReconcileContext,
        runId: RunId,
        hasCandidate: Boolean,
    ): Run? {
        val run =
            synchronized(sessionRequestLock) {
                visibleSessionRuns(context.sessionId).lastOrNull { it.id == runId }
                    ?: observationStateFor(context.sessionId, runId)?.run
                    ?: unresolvedLocalRunIds[recoverySessionKey(context.sessionId)]
                        ?.takeIf { runId in it }
                        ?.let { Run(runId, context.sessionId, UNCERTAIN_RUN_STATUS) }
                    ?: uncertainSubmissionRunIds[context.sessionId]
                        ?.takeIf { runId == it }
                        ?.let { Run(runId, context.sessionId, UNCERTAIN_RUN_STATUS) }
            } ?: return null
        val reconciliation =
            reconcileRun(
                sessionId = context.sessionId,
                runId = run.id,
                requestConnectionGeneration = context.requestConnectionGeneration,
                requestSessionGeneration = context.requestSessionGeneration,
                sessionGateway = context.sessionGateway,
            )
        val reconciledRun = reconciliation?.run
        return continuedObservationRun(reconciledRun, run, hasCandidate)
    }

    private fun continuedObservationRun(
        reconciledRun: Run?,
        fallback: Run,
        hasCandidate: Boolean,
    ): Run? {
        val reconciledIsActive = reconciledRun?.isActive() == true
        val fallbackIsActive = reconciledRun == null && !hasCandidate && fallback.isActive()
        return if (reconciledIsActive || fallbackIsActive) reconciledRun ?: fallback else null
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
                return@synchronized
            }
            val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
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

    private fun reconcileRun(
        sessionId: SessionId,
        runId: RunId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
        sessionGateway: SessionGatewayPort,
    ): AuthoritativeRunReconciliation? {
        val runGateway =
            if (beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) {
                synchronized(sessionRequestLock) {
                    runGateway?.takeIf {
                        connectionGeneration == requestConnectionGeneration &&
                            sessionRequestGeneration == requestSessionGeneration &&
                            _uiState.value.sessionList?.openedSession?.session?.id == sessionId
                    }
                }
            } else {
                null
            } ?: return null
        return try {
            val result = ReconcileRun(runGateway, sessionGateway).execute(runId, sessionId)
            applyAuthoritativeRunReconciliation(
                request =
                    AuthoritativeReconciliationRequest(
                        sessionId = sessionId,
                        connectionGeneration = requestConnectionGeneration,
                        sessionGeneration = requestSessionGeneration,
                    ),
                reconciliation = result,
            )
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
            val reconciliationIsSuperseded =
                connectionGeneration != requestConnectionGeneration ||
                    sessionRequestGeneration != requestSessionGeneration ||
                    opened?.session?.id != sessionId ||
                    reconcilingSessions[sessionId] == requestSessionGeneration
            if (reconciliationIsSuperseded) {
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
                return@synchronized
            }
            val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
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
        request: AuthoritativeReconciliationRequest,
        reconciliation: AuthoritativeRunReconciliation,
    ) {
        val application = applyReconciliationLocked(request, reconciliation)
        application.observationToClose?.close()
        application.observationJobToCancel?.cancel()
        removeReconciledRecoveryEntry(
            recoveryEntryToRemove = application.recoveryEntryToRemove,
            recoveryEndpointToRemove = application.recoveryEndpointToRemove,
            sessionId = request.sessionId,
            requestConnectionGeneration = request.connectionGeneration,
            requestHistoryGeneration = request.historyGeneration,
        )
    }

    private fun applyReconciliationLocked(
        request: AuthoritativeReconciliationRequest,
        reconciliation: AuthoritativeRunReconciliation,
    ): ReconciledRunApplication =
        synchronized(sessionRequestLock) {
            val requestIsCurrent =
                connectionGeneration == request.connectionGeneration &&
                    (!request.updateVisibleUi || sessionRequestGeneration == request.sessionGeneration)
            if (!requestIsCurrent) return@synchronized ReconciledRunApplication()
            val shouldUpdateVisibleUi =
                request.updateVisibleUi &&
                    request.sessionGeneration != null &&
                    sessionRequestGeneration == request.sessionGeneration
            val run = reconciliation.run
            val decision = reconciliation.decision
            val terminalRunIds = terminalRunIdsFor(request.sessionId, reconciliation)
            val session = visibleSessionTarget(request.sessionId, shouldUpdateVisibleUi)
            if (historyGenerationMatches(request.sessionId, request.historyGeneration)) {
                val release = recordReconciledRunState(request.sessionId, reconciliation, terminalRunIds)
                if (decision == RunReconciliationDecision.CONFIRMED) {
                    val handles = confirmedRecoveryHandles(request, run)
                    val canClearSendState =
                        clearConfirmedSubmissionState(request.sessionId, reconciliation, release.wasActivelyObserved)
                    updateConfirmedRunSessionUi(
                        sessionId = request.sessionId,
                        reconciliation = reconciliation,
                        canClearSendState = canClearSendState,
                        session = session,
                    )
                    ReconciledRunApplication(
                        observationJobToCancel = release.job,
                        observationToClose = release.observation,
                        recoveryEntryToRemove = handles.first,
                        recoveryEndpointToRemove = handles.second,
                    )
                } else {
                    updateUnconfirmedRunSessionUi(
                        sessionId = request.sessionId,
                        run = run,
                        session = session,
                    )
                    ReconciledRunApplication(
                        observationJobToCancel = release.job,
                        observationToClose = release.observation,
                    )
                }
            } else if (
                decision == RunReconciliationDecision.CONFIRMED &&
                !run.isActive() &&
                historyConfirmsTerminalRun(request.sessionId, run.id)
            ) {
                ReconciledRunApplication(
                    recoveryEntryToRemove = RunRecoveryEntry(sessionId = request.sessionId, runId = run.id),
                    recoveryEndpointToRemove = _uiState.value.endpoint.takeIf(String::isNotBlank),
                )
            } else {
                ReconciledRunApplication()
            }
        }

    private fun confirmedRecoveryHandles(
        request: AuthoritativeReconciliationRequest,
        run: Run,
    ): Pair<RunRecoveryEntry?, String?> =
        if (run.isActive()) {
            null to null
        } else {
            RunRecoveryEntry(sessionId = request.sessionId, runId = run.id) to
                _uiState.value.endpoint.takeIf(String::isNotBlank)
        }

    private fun visibleSessionTarget(
        sessionId: SessionId,
        shouldUpdate: Boolean,
    ): Pair<SessionListUiState, OpenSessionUiState>? =
        if (!shouldUpdate) {
            null
        } else {
            openedSessionFor(sessionId)
        }

    private fun terminalRunIdsFor(
        sessionId: SessionId,
        reconciliation: AuthoritativeRunReconciliation,
    ): Set<RunId> {
        val run = reconciliation.run
        val currentlyObservedRunId = runObservationRunIds[sessionId]
        val terminalRunIds =
            reconciliation.history.runs()
                .filterNot(Run::isActive)
                .filter { it.id != currentlyObservedRunId || it.id == run.id }
                .mapTo(mutableSetOf()) { it.id }
        if (reconciliation.decision != RunReconciliationDecision.CONFIRMED) {
            terminalRunIds.remove(run.id)
        }
        return terminalRunIds
    }

    private fun historyGenerationMatches(
        sessionId: SessionId,
        requestHistoryGeneration: Long?,
    ): Boolean =
        requestHistoryGeneration == null ||
            (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) == requestHistoryGeneration

    private fun historyConfirmsTerminalRun(
        sessionId: SessionId,
        runId: RunId,
    ): Boolean =
        authoritativeSessionRuns[sessionId]
            .orEmpty()
            .any { knownRun -> knownRun.id == runId && !knownRun.isActive() }

    private fun recordReconciledRunState(
        sessionId: SessionId,
        reconciliation: AuthoritativeRunReconciliation,
        terminalRunIds: Set<RunId>,
    ): ReconciledObservationRelease {
        val run = reconciliation.run
        val wasActivelyObserved = runObservationRunIds[sessionId] == run.id
        forgetConfirmedObservationStates(sessionId, terminalRunIds)
        val incomingAuthoritativeRuns = reconciliation.history.runs() + run
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
        if (!run.isActive() && runObservationRunIds[sessionId] == run.id) {
            val job = runObservationJobs[sessionId]
            val observation = runObservations.remove(sessionId)
            runObservationRunIds.remove(sessionId)
            return ReconciledObservationRelease(
                job = job,
                observation = observation,
                wasActivelyObserved = wasActivelyObserved,
            )
        }
        return ReconciledObservationRelease(wasActivelyObserved = wasActivelyObserved)
    }

    private fun clearConfirmedSubmissionState(
        sessionId: SessionId,
        reconciliation: AuthoritativeRunReconciliation,
        wasActivelyObserved: Boolean,
    ): Boolean {
        val run = reconciliation.run
        val canClearSendState = resolveBoundSubmission(sessionId, run)
        if (!run.isActive()) {
            forgetUnresolvedLocalRun(sessionId, run.id)
        }
        if (canClearSendState) {
            uncertainSubmissionRunIds.remove(sessionId)
            uncertainSubmissionAttemptIds.remove(sessionId)
            sessionSendErrors.remove(sessionId)
            val uncertainDraft = uncertainSendDrafts.remove(sessionId)
            if (uncertainDraft != null && sessionDraftRevisions[sessionId] == uncertainDraft.revision) {
                sessionDrafts.remove(sessionId)
            }
            ambiguousSubmissionSessions.remove(sessionId)
        }
        forgetObservationState(sessionId, run.id)
        if (wasActivelyObserved) {
            postTerminalRunStatusNotificationOnce(run, run.toRunPresentationState())
        }
        return canClearSendState
    }

    private fun resolveBoundSubmission(
        sessionId: SessionId,
        run: Run,
    ): Boolean {
        val pendingKey = pendingRunSubmissionKey(sessionId)
        val uncertaintySnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
        val unresolvedAttemptId =
            unresolvedLocalRunAttempts[recoverySessionKey(sessionId)]?.get(run.id)
        val trackedAttemptId =
            uncertainSubmissionAttemptIds[sessionId] ?: unresolvedAttemptId
        val hasBoundSubmission =
            uncertainSubmissionRunIds[sessionId] == run.id ||
                unresolvedLocalRunIds[recoverySessionKey(sessionId)]?.contains(run.id) == true ||
                uncertaintySnapshot?.boundRunId == run.id
        val markerMatchesTrackedAttempt =
            trackedAttemptId == null ||
                uncertaintySnapshot == null ||
                uncertaintySnapshot.attemptId == trackedAttemptId
        val isBoundSubmission = hasBoundSubmission && markerMatchesTrackedAttempt
        val uncertaintyRemoved =
            !isBoundSubmission ||
                run.isActive() ||
                uncertaintySnapshot == null ||
                runCatching {
                    runSubmissionUncertaintyStore.removeIfSnapshotMatches(pendingKey, uncertaintySnapshot)
                }.getOrDefault(false)
        return isBoundSubmission && uncertaintyRemoved
    }

    private fun updateConfirmedRunSessionUi(
        sessionId: SessionId,
        reconciliation: AuthoritativeRunReconciliation,
        canClearSendState: Boolean,
        session: Pair<SessionListUiState, OpenSessionUiState>?,
    ) {
        if (session == null) return
        val (current, opened) = session
        val knownRuns = visibleSessionRuns(sessionId)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        val latestRun = knownRuns.latestRun()
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                messages = reconciliation.history.toMessageUiStates(),
                                composerText = sessionDrafts[sessionId].orEmpty(),
                                sendErrorCategory = if (canClearSendState) null else opened.sendErrorCategory,
                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                latestRun = latestRun,
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState =
                                    latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                                errorCategory = null,
                                isStale = false,
                            ),
                    ),
            )
    }

    private fun updateUnconfirmedRunSessionUi(
        sessionId: SessionId,
        run: Run,
        session: Pair<SessionListUiState, OpenSessionUiState>?,
    ) {
        val previous = observationStateFor(sessionId, run.id)
        val uncertainState = uncertainObservationState(run, previous)
        rememberObservationState(uncertainState)
        if (session == null) return
        val (current, opened) = session
        val knownRuns = visibleSessionRuns(sessionId)
        val latestRun = knownRuns.latestRun()
        val latestObservation = latestObservationState(sessionId, knownRuns)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                latestRun = latestRun,
                                activeRuns = knownRuns.activeRuns(),
                                sendErrorCategory = opened.sendErrorCategory,
                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                latestRunState =
                                    latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                isStale = true,
                            ),
                    ),
            )
    }

    private fun removeReconciledRecoveryEntry(
        recoveryEntryToRemove: RunRecoveryEntry?,
        recoveryEndpointToRemove: String?,
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestHistoryGeneration: Long?,
    ) {
        var shouldRemoveRecoveryEntry = false
        recoveryEntryToRemove?.let { entry ->
            synchronized(sessionRequestLock) {
                val endpoint = recoveryEndpointToRemove
                val historyConfirmsTerminal = historyConfirmsTerminalRun(entry.sessionId, entry.runId)
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
                return@synchronized
            }
            val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
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
                                    latestRunState =
                                        latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                    latestRunRetryAvailable =
                                        latestRunRetryAvailable(
                                            knownRuns,
                                            latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                        ),
                                    activeResponse = observedMessageUiState(latestObservation),
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
        ui: TimedOutSendUiContext,
        errorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
    ): Boolean {
        unresolvedSubmissionSessions += sessionId
        sessionSendErrors[sessionId] = errorCategory
        val pendingKey = pendingRunSubmissionKey(sessionId)
        if (runSubmissionUncertaintyStore.contains(pendingKey)) {
            uncertainSubmissionAttemptIds[sessionId] =
                pendingTimedOutSends[sessionId]?.attemptId
                    ?: runSubmissionUncertaintyStore.attemptId(pendingKey)
                    ?: LEGACY_ATTEMPT_ID
        }
        pendingRunDrafts.remove(sessionId)?.let { uncertainSendDrafts[sessionId] = it }
            ?: ui.recoveryDraft?.let { uncertainSendDrafts[sessionId] = it }
        val knownRuns = visibleSessionRuns(sessionId)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    ui.current.copy(
                        openedSession =
                            ui.opened.copy(
                                messages = ui.authoritativeMessages ?: ui.opened.messages,
                                isSending = false,
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState =
                                    latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                                sendErrorCategory = errorCategory,
                                hasUnresolvedSubmission = true,
                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                isStale = true,
                            ),
                    ),
            )
        return true
    }

    private fun showOpenedSessionFailure(requestGeneration: Long) {
        updateCurrentSessionRequest(requestGeneration) { current ->
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
                val sessionList = state.sessionList ?: return@synchronized null
                val offset = sessionList.nextOffset ?: return@synchronized null
                if (sessionList.blocksLoadingMore()) return@synchronized null

                val request =
                    SessionRequestContext(beginSessionRequest(), sessionList.searchQuery, offset)
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                isLoadingMore = true,
                                errorCategory = null,
                            ),
                    )
                loadMorePageJob(gateway, request)
            } ?: return
        job.start()
    }

    private fun loadMorePageJob(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
    ): Job =
        createSessionJob {
            try {
                val page = LoadSessionList(gateway).execute(sessionListRequest(request.offset))
                updateCurrentSessionRequest(request.generation, request.offset) { current ->
                    current.copy(
                        sessions =
                            mergeSessions(
                                current.sessions,
                                page.sessions.map { it.toSessionItemUiState() },
                            ),
                        openedSession = current.openedSession?.withSessionMetadata(page.sessions),
                        nextOffset = page.nextOffset,
                        isLoadingMore = false,
                        isStale = false,
                        isUnavailable = false,
                        errorCategory = null,
                    )
                }
                recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: GatewayException) {
                showSessionListFailure(request.generation, request.offset, preserveSessions = true)
            } catch (_: Exception) {
                showSessionListFailure(request.generation, request.offset, preserveSessions = true)
            }
        }

    private fun showCreateSession() {
        var observationToClose: RunEventObservation? = null
        var observationJobToCancel: Job? = null
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            if (current.blocksCreateSession()) return
            current.openedSession?.session?.id?.let { sessionId ->
                val released = releaseRunObservation(sessionId)
                observationJobToCancel = released.job
                observationToClose = released.observation
                beginSessionRequest()
            }
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            createSession = SessionCreationUiState(),
                            openedSession = null,
                            errorCategory = null,
                        ),
                )
        }
        observationJobToCancel?.cancel()
        observationToClose?.close()
    }

    private fun updateCreateSessionTitle(value: String) {
        val current = _uiState.value.sessionList ?: return
        val creation = current.createSession?.takeIf { !it.isSubmitting } ?: return
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

    private fun SessionListUiState.sessionForMutation(sessionId: SessionId): SessionItemUiState? =
        sessions.firstOrNull { it.id == sessionId }
            ?: openedSession?.session?.takeIf { it.id == sessionId }

    private fun showRenameSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList?.takeIf { it.allowsSessionMutation() } ?: return
            val mutation = current.sessionMutations[sessionId]
            val session = current.sessionForMutation(sessionId)
            if (mutation?.pendingAction != null || mutation?.delete != null || session == null) return
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
            val mutation = current.sessionMutations[sessionId]?.takeIf { it.pendingAction == null }
            val rename = mutation?.rename?.takeIf { !it.isSubmitting }
            if (mutation == null || rename == null) return
            val updated =
                mutation.copy(
                    rename = rename.copy(titleDraft = value, errorCategory = null),
                    errorCategory = null,
                    retryAction = null,
                )
            val key = recoverySessionKey(sessionId)
            if (key in unresolvedSessionMutations) {
                unresolvedSessionMutations[key] = updated
            }
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations = current.sessionMutations + (sessionId to updated),
                        ),
                )
        }
    }

    private fun cancelRenameSession(sessionId: SessionId) {
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return
            val mutation = current.sessionMutations[sessionId]?.takeIf { it.pendingAction == null } ?: return
            val remaining = mutation.copy(rename = null)
            unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
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
                val current =
                    _uiState.value.sessionList
                        ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }
                        ?: return@synchronized null
                val mutation = current.sessionMutations[sessionId]
                val rename = mutation?.rename
                if (mutation == null || rename == null) return@synchronized null
                if (mutation.pendingAction != null || rename.isSubmitting) return@synchronized null
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
                    return@synchronized null
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
                val request = beginSessionMutation(sessionId, gateway)
                unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
                submitRenameSession(gateway, sessionId, title, request)
            } ?: return
        job.start()
    }

    private fun submitRenameSession(
        gateway: SessionGatewayPort,
        sessionId: SessionId,
        title: String,
        request: SessionMutationRequest,
    ): Job =
        createMutationJob(request) {
            try {
                val confirmed = RenameSession(gateway).execute(sessionId, title)
                applyConfirmedSession(confirmed, request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showRenameFailure(request)
            }
        }

    private fun showRenameFailure(request: SessionMutationRequest) {
        synchronized(sessionRequestLock) {
            if (!isCurrentSessionMutation(request)) return
            val sessionId = request.sessionId
            val current = _uiState.value.sessionList
            val mutation = current?.sessionMutations?.get(sessionId)
            val rename = mutation?.rename
            if (current == null || mutation == null || rename == null) return
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
                                                        errorCategory =
                                                            SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
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
            sessionRuns[sessionId].orEmpty() +
                allObservationStates(sessionId)
                    .filter { state -> state.run.id in localRunIds }
                    .map(RunObservationState::run)
        sessionRuns[sessionId] = mergeRuns(emptyList(), retainedLocalRuns)
        return visibleSessionRuns(sessionId)
    }

    private fun beginSessionMutation(
        sessionId: SessionId,
        gateway: SessionGatewayPort,
    ): SessionMutationRequest {
        val request =
            SessionMutationRequest(
                sessionId = sessionId,
                gateway = gateway,
                connectionGeneration = connectionGeneration,
                attemptId = ++nextMutationAttemptId,
            )
        mutationOwners[sessionId] = request
        return request
    }

    private fun isCurrentSessionMutation(request: SessionMutationRequest): Boolean {
        val current = mutationOwners[request.sessionId]
        return current?.attemptId == request.attemptId &&
            connectionGeneration == request.connectionGeneration &&
            sessionGateway === request.gateway
    }

    private fun createMutationJob(
        request: SessionMutationRequest,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    synchronized(sessionRequestLock) {
                        if (mutationJobs[request.sessionId] === job) {
                            mutationJobs.remove(request.sessionId)
                        }
                        if (mutationOwners[request.sessionId]?.attemptId == request.attemptId) {
                            mutationOwners.remove(request.sessionId)
                        }
                    }
                }
            }
        synchronized(sessionRequestLock) {
            mutationJobs[request.sessionId] = job
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

    private fun applyConfirmedSession(
        session: Session,
        request: SessionMutationRequest,
    ) {
        synchronized(sessionRequestLock) {
            if (!isCurrentSessionMutation(request)) return
            val current =
                _uiState.value.sessionList?.takeIf { it.sessionForMutation(session.id) != null } ?: return
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
            unresolvedSessionMutations.remove(recoverySessionKey(session.id))
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
                val current =
                    _uiState.value.sessionList
                        ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }
                        ?: return@synchronized null
                val mutation = current.sessionMutations[sessionId] ?: SessionMutationUiState()
                val blocked =
                    current.sessionForMutation(sessionId) == null ||
                        mutation.pendingAction != null ||
                        mutation.rename != null ||
                        mutation.delete != null
                if (blocked) return@synchronized null
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
                val request = beginSessionMutation(sessionId, gateway)
                unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
                createMutationJob(request) {
                    try {
                        val result =
                            if (pinned) {
                                PinSession(gateway).execute(sessionId)
                            } else {
                                UnpinSession(gateway).execute(sessionId)
                            }
                        applyConfirmedPin(result.sessionId, result.pinned, request)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showPinFailure(request, action)
                    }
                }
            } ?: return
        job.start()
    }

    private fun applyConfirmedPin(
        sessionId: SessionId,
        pinned: Boolean,
        request: SessionMutationRequest,
    ) {
        val refreshJob =
            synchronized(sessionRequestLock) {
                if (!isCurrentSessionMutation(request)) return@synchronized null
                val current = _uiState.value.sessionList ?: return@synchronized null
                if (current.sessionForMutation(sessionId) == null) return@synchronized null
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
                                sessionMutations =
                                    mutationMapAfter(sessionId, remainingMutation, current.sessionMutations),
                            ),
                    )
                unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
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
        request: SessionMutationRequest,
        action: SessionMutationAction,
    ) {
        synchronized(sessionRequestLock) {
            if (!isCurrentSessionMutation(request)) return
            val sessionId = request.sessionId
            val current = _uiState.value.sessionList
            val mutation = current?.sessionMutations?.get(sessionId)
            if (current == null || mutation == null) return
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
            val current =
                _uiState.value.sessionList
                    ?.takeIf { it.allowsSessionMutation() && it.sessionForMutation(sessionId) != null }
                    ?: return
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
            val mutation = current.sessionMutations[sessionId]?.takeIf { it.pendingAction == null } ?: return
            val remaining = mutation.copy(delete = null, errorCategory = null, retryAction = null)
            unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
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
                val current =
                    _uiState.value.sessionList
                        ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }
                        ?: return@synchronized null
                val mutation = current.sessionMutations[sessionId]
                val delete = mutation?.delete
                if (mutation == null || delete == null) return@synchronized null
                if (mutation.pendingAction != null || delete.isSubmitting) return@synchronized null
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
                val request = beginSessionMutation(sessionId, gateway)
                unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
                createMutationJob(request) {
                    try {
                        DeleteSession(gateway).execute(sessionId)
                        removeConfirmedSession(sessionId, request)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        showDeleteFailure(request)
                    }
                }
            } ?: return
        job.start()
    }

    private fun showDeleteFailure(request: SessionMutationRequest) {
        synchronized(sessionRequestLock) {
            if (!isCurrentSessionMutation(request)) return
            val sessionId = request.sessionId
            val current = _uiState.value.sessionList
            val mutation = current?.sessionMutations?.get(sessionId)
            val delete = mutation?.delete
            if (current == null || mutation == null || delete == null) return
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

    private fun removeConfirmedSession(
        sessionId: SessionId,
        request: SessionMutationRequest,
    ) {
        var observationJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        val refreshJob =
            synchronized(sessionRequestLock) {
                if (!isCurrentSessionMutation(request)) return@synchronized null
                val current = _uiState.value.sessionList ?: return@synchronized null
                val shouldRefresh = current.nextOffset != null
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
                unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
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
        if (mutation.hasNoPendingWork()) mutations - sessionId else mutations + (sessionId to mutation)

    private fun confirmCreateSession() {
        val gateway = sessionGateway ?: return
        val job =
            synchronized(sessionRequestLock) {
                val state = _uiState.value
                val sessionList = state.sessionList ?: return@synchronized null
                val creation = sessionList.createSession ?: return@synchronized null
                if (creation.isSubmitting || sessionList.isUnavailable) return@synchronized null

                val title = creation.titleDraft.trim().takeIf(String::isNotEmpty)
                val query = sessionList.searchQuery
                val requestGeneration = beginSessionRequest()
                val request =
                    CreateSessionRequest(
                        context = SessionRequestContext(requestGeneration, query, offset = null),
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
            } ?: return
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
                        LoadSessionList(gateway).execute(sessionListRequest(offset = null))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                val openedSessionUiState =
                    openedSession.toOpenSessionUiState(
                        OpenSessionOverlay(messages = openedSession.history.toMessageUiStates()),
                    )
                updateCurrentSessionRequest(request.context.generation) { current ->
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
                        nextOffset = refreshedPage?.nextOffset,
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
                handleCreateSessionFailure(request.context, createdSession)
            } catch (_: Exception) {
                handleCreateSessionFailure(request.context, createdSession)
            }
        }

    private fun handleCreateSessionFailure(
        context: SessionRequestContext,
        createdSession: Session?,
    ) {
        if (createdSession == null) {
            showCreateSessionFailure(context.generation)
        } else {
            showCreatedSessionFailure(context.generation, context.query, createdSession)
        }
    }

    private fun showCreateSessionFailure(requestGeneration: Long) {
        updateCurrentSessionRequest(requestGeneration) { current ->
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
        updateCurrentSessionRequest(requestGeneration) { current ->
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

    private fun OpenSessionUiState.withSessionMetadata(sessions: List<Session>): OpenSessionUiState =
        sessions.firstOrNull { it.id == session.id }
            ?.let { copy(session = it.toSessionItemUiState()) }
            ?: this

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
            val request = SessionRequestContext(requestGeneration, query, offset = null)
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
                            nextOffset = if (preserveSessions) current.nextOffset else null,
                            sessionMutations =
                                restoreUnresolvedSessionMutations(
                                    if (preserveSessions) {
                                        unsentRenameDrafts(current.sessionMutations)
                                    } else {
                                        emptyMap()
                                    },
                                ),
                        ),
                )
            createSessionJob {
                loadFirstPage(gateway, request, preserveSessions)
            }
        }

    private fun loadFirstPage(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        preserveSessions: Boolean,
    ) {
        try {
            val page = LoadSessionList(gateway).execute(sessionListRequest(request.offset))
            updateCurrentSessionRequest(request.generation) { latest ->
                latest.copy(
                    sessions =
                        mergeSessions(
                            emptyList(),
                            page.sessions.map { it.toSessionItemUiState() },
                        ),
                    openedSession = latest.openedSession?.withSessionMetadata(page.sessions),
                    sessionMutations =
                        restoreUnresolvedSessionMutations(
                            retainRenameDrafts(
                                latest.sessionMutations,
                                page.sessions.map { it.id } + listOfNotNull(latest.openedSession?.session?.id),
                            ),
                        ),
                    nextOffset = page.nextOffset,
                    isLoading = false,
                    isRefreshing = false,
                    isSearching = false,
                    isLoadingMore = false,
                    isStale = false,
                    isUnavailable = false,
                    errorCategory = null,
                )
            }
            recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showSessionListFailure(
                request.generation,
                offset = null,
                preserveSessions = preserveSessions,
            )
        } catch (_: Exception) {
            showSessionListFailure(
                request.generation,
                offset = null,
                preserveSessions = preserveSessions,
            )
        }
    }

    private fun sessionListRequest(offset: Int?): SessionListRequest =
        SessionListRequest(
            offset = offset ?: 0,
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
        offset: Int? = null,
        transform: (SessionListUiState) -> SessionListUiState,
    ): Boolean =
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return@synchronized false
            val offsetIsStale = offset != null && current.nextOffset != offset
            if (requestGeneration != sessionRequestGeneration || offsetIsStale) {
                false
            } else {
                _uiState.value = _uiState.value.copy(sessionList = transform(current))
                true
            }
        }

    private fun showSessionListFailure(
        requestGeneration: Long,
        offset: Int?,
        preserveSessions: Boolean,
    ) {
        recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.FAILED)
        updateCurrentSessionRequest(requestGeneration, offset) { current ->
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
                val sessionList = state.sessionList ?: return@synchronized null
                val sessionCanOpen = sessionList.sessions.any { it.id == sessionId }
                val mutationPending = sessionList.sessionMutations[sessionId]?.pendingAction != null
                if (sessionList.blocksSessionOpen() || !sessionCanOpen || mutationPending) {
                    return@synchronized null
                }
                val replacedSessionId = sessionList.openedSession?.session?.id?.takeIf { it != sessionId }
                val request =
                    SessionRequestContext(beginSessionRequest(), sessionList.searchQuery, offset = null)
                val requestConnectionGeneration = connectionGeneration
                _uiState.value =
                    state.copy(
                        sessionList =
                            sessionList.copy(
                                openingSessionId = sessionId,
                                errorCategory = null,
                            ),
                    )
                createSessionJob {
                    openSessionJob(gateway, request, requestConnectionGeneration, sessionId, replacedSessionId)
                }
            } ?: return
        job.start()
    }

    private suspend fun openSessionJob(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        requestConnectionGeneration: Long,
        sessionId: SessionId,
        replacedSessionId: SessionId?,
    ) {
        try {
            val openedSession = OpenSession(gateway).execute(sessionId)
            val applied =
                updateCurrentSessionRequest(request.generation) { current ->
                    applyOpenedSessionResult(current, openedSession, sessionId)
                }
            if (applied) {
                applyOpenedSessionOutcome(
                    gateway = gateway,
                    request = request,
                    requestConnectionGeneration = requestConnectionGeneration,
                    openedSession = openedSession,
                    replacedSessionId = replacedSessionId,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showSessionOpenFailure(request.generation, sessionId)
        } catch (_: Exception) {
            showSessionOpenFailure(request.generation, sessionId)
        }
    }

    private fun applyOpenedSessionResult(
        current: SessionListUiState,
        openedSession: OpenedSession,
        sessionId: SessionId,
    ): SessionListUiState {
        val authoritativeSession = openedSession.session.toSessionItemUiState()
        val knownRuns = rememberSessionRuns(sessionId, openedSession)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        val latestRun = knownRuns.latestRun()
        val recoveryLoadBlocksSession =
            recoveryLoadPending ||
                recoveryLoadFailed ||
                sessionId in connectionRecoverySessionCounts ||
                sessionId in recoverySessionCounts
        return current.copy(
            sessions =
                current.sessions
                    .map { item ->
                        if (item.id == sessionId) authoritativeSession else item
                    }
                    .orderedSessions(),
            sessionMutations = retainSessionMutation(current.sessionMutations, sessionId),
            openingSessionId = null,
            isStale = false,
            isUnavailable = false,
            openedSession =
                openedSession.toOpenSessionUiState(
                    OpenSessionOverlay(
                        messages = openedSession.history.toMessageUiStates(),
                        composerText = sessionDrafts[sessionId].orEmpty(),
                        sendErrorCategory = sendErrorCategoryFor(sessionId),
                        hasUnresolvedSubmission =
                            hasUnresolvedSubmission(sessionId) || recoveryLoadBlocksSession,
                        latestRun = latestRun,
                        activeRuns = knownRuns.activeRuns(),
                        isSending = runJobs.containsKey(sessionId),
                        latestRunState =
                            latestObservation?.state ?: latestRun?.toRunPresentationState(),
                        latestRunRetryAvailable =
                            latestRunRetryAvailable(
                                knownRuns,
                                latestObservation?.state ?: latestRun?.toRunPresentationState(),
                            ),
                        activeResponse = observedMessageUiState(latestObservation),
                    ),
                ).copy(
                    isRefreshing = recoveryLoadBlocksSession,
                    isReconciliationInProgress =
                        openedSession.history.latestRun() != null || recoveryLoadBlocksSession,
                ),
            errorCategory = null,
        )
    }

    private suspend fun applyOpenedSessionOutcome(
        gateway: SessionGatewayPort,
        request: SessionRequestContext,
        requestConnectionGeneration: Long,
        openedSession: OpenedSession,
        replacedSessionId: SessionId?,
    ) {
        replacedSessionId?.let { releasedSessionId ->
            val released =
                synchronized(sessionRequestLock) { releaseRunObservation(releasedSessionId) }
            released.job?.cancel()
            released.observation?.close()
        }
        val sessionId = openedSession.session.id
        val pendingTimeoutRecovery =
            synchronized(sessionRequestLock) {
                pendingTimedOutSends[sessionId]
            }
        if (pendingTimeoutRecovery != null) {
            reconcileTimedOutSendNow(
                sessionId = sessionId,
                request = ReconciliationRequest(requestConnectionGeneration, request.generation),
                knownRunIds = pendingTimeoutRecovery.knownRunIds,
            )
        } else {
            val pendingSettledRecovery = prepareSettledSubmissionRecovery(sessionId)
            if (pendingSettledRecovery != null) {
                reconcileTimedOutSendNow(
                    sessionId = sessionId,
                    request = ReconciliationRequest(requestConnectionGeneration, request.generation),
                    knownRunIds = pendingSettledRecovery.knownRunIds,
                    resolveWhenNoNewRun = true,
                )
            } else {
                reconcileOpenedRun(
                    OpenedRunReconcileContext(
                        sessionId = sessionId,
                        requestSessionGeneration = request.generation,
                        requestConnectionGeneration = requestConnectionGeneration,
                        sessionGateway = gateway,
                        runIdToReconcile = historyRunIdToReconcile(sessionId, openedSession),
                        restartObservation = true,
                        clearRefreshWhenNoRun = true,
                    ),
                )
            }
        }
    }

    private fun showSessionOpenFailure(
        requestGeneration: Long,
        sessionId: SessionId,
    ) {
        updateCurrentSessionRequest(requestGeneration) { current ->
            current.copy(
                sessionMutations = retainRenameDraft(current.sessionMutations, sessionId),
                openingSessionId = null,
                isStale = true,
                isUnavailable = true,
                errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
            )
        }
    }

    private fun releaseRunObservation(sessionId: SessionId): RunObservationRelease {
        retainUnconfirmedTerminalRuns(sessionId)
        val job = runObservationJobs.remove(sessionId)
        val observation = runObservations.remove(sessionId)
        runObservationRunIds.remove(sessionId)
        return RunObservationRelease(job = job, observation = observation)
    }

    private data class RunObservationRelease(
        val job: Job?,
        val observation: RunEventObservation?,
    )

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
                val released = releaseRunObservation(sessionId)
                observationJobToCancel = released.job
                observationToClose = released.observation
            }
            beginSessionRequest()
            val retainedSession =
                current.openedSession?.session?.takeIf { session ->
                    current.sessionMutations.containsKey(session.id) && current.sessions.none { it.id == session.id }
                }
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions =
                                retainedSession?.let { mergeSessions(current.sessions, listOf(it)) }
                                    ?: current.sessions,
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
            sessionDraftRevisions[opened.session.id] =
                sessionDraftRevisions[opened.session.id]?.plus(1) ?: 1L
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
                    Triple(
                        sessionId,
                        connectionGeneration,
                        runSubmissionUncertaintyStore.knownRunIds(key) to
                            (runSubmissionUncertaintyStore.attemptId(key) ?: LEGACY_ATTEMPT_ID),
                    )
                }
            }
        if (retryContext == null) {
            sendMessage()
            return
        }
        val (sessionId, requestConnectionGeneration, recovery) = retryContext
        val (knownRunIds, attemptId) = recovery
        scope.launch {
            val reconciled =
                reconcileTimedOutSend(
                    TimedOutSendReconciliationRequest(
                        sessionId = sessionId,
                        connectionGeneration = requestConnectionGeneration,
                        knownRunIds = knownRunIds,
                        attemptId = attemptId,
                        resolveWhenNoNewRun = true,
                    ),
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
        val input =
            synchronized(sessionRequestLock) {
                _uiState.value.sessionList?.openedSession?.composerText?.takeIf { it.isNotBlank() }
            } ?: return
        launchRunSubmission(input = input, recordDraft = true)
    }

    private fun retryRun(runId: RunId) {
        val originalMessage =
            synchronized(sessionRequestLock) {
                val opened = _uiState.value.sessionList?.openedSession ?: return@synchronized null
                val sessionId = opened.session.id
                val failedState =
                    visibleSessionRuns(sessionId).lastOrNull { it.id == runId }?.toRunPresentationState()
                        ?: observationStateFor(sessionId, runId)?.state
                        ?: opened.messages.lastOrNull { it.runId == runId }?.runState
                        ?: opened.messages
                            .lastOrNull { it.runId == runId && it.isFailedRun }
                            ?.let { RunPresentationState.FAILED }
                val original =
                    submittedRunInputs[runId]
                        ?: opened.messages
                            .lastOrNull { it.runId == runId && it.role == "user" }
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                if (!isRunRetryEligible(failedState, original)) return@synchronized null
                original
            } ?: return
        launchRunSubmission(input = originalMessage, recordDraft = false, rejectRunId = runId)
    }

    private fun latestRunRetryAvailable(
        runs: List<Run>,
        latestRunState: RunPresentationState?,
    ): Boolean {
        val latestRun = runs.latestRun() ?: return false
        return isRunRetryEligible(latestRunState, submittedRunInputs[latestRun.id])
    }

    private fun launchRunSubmission(
        input: String,
        recordDraft: Boolean,
        rejectRunId: RunId? = null,
    ) {
        val gateway = runGateway ?: return
        val submission = prepareRunSubmission(gateway, input, recordDraft, rejectRunId) ?: return
        claimAndStartRunJob(submission)
    }

    private fun prepareRunSubmission(
        gateway: RunGatewayPort,
        input: String,
        recordDraft: Boolean,
        rejectRunId: RunId?,
    ): PreparedRunSubmission? =
        synchronized(sessionRequestLock) {
            val current = _uiState.value.sessionList ?: return@synchronized null
            val opened = current.openedSession ?: return@synchronized null
            val sessionId = opened.session.id
            val requestConnectionGeneration = connectionGeneration
            val (knownRuns, submissionState) = submissionRunsAndState(opened, sessionId)
            if (!submissionState.canSubmit || current.hasPendingMutation || blocksRunSubmission(opened, sessionId)) {
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
            if (recordDraft) {
                recordSubmissionDraft(sessionId, input)
            }
            val requestEndpoint = _uiState.value.endpoint
            val recoverySessionKey = RecoverySessionKey(requestEndpoint, sessionId)
            val knownRunIds = knownRuns.mapTo(mutableSetOf()) { it.id }
            val attemptId = UUID.randomUUID().toString()
            val pendingCreate = PendingCreateState(knownRunIds, attemptId)
            pendingCreateSessions[recoverySessionKey] = pendingCreate
            val work =
                RunSubmissionWork(
                    gateway = gateway,
                    input = input,
                    rejectRunId = rejectRunId,
                    sessionId = sessionId,
                    requestConnectionGeneration = requestConnectionGeneration,
                    requestEndpoint = requestEndpoint,
                    recoverySessionKey = recoverySessionKey,
                    pendingCreate = pendingCreate,
                    attemptId = attemptId,
                    knownRunIds = knownRunIds,
                )
            PreparedRunSubmission(
                job = createRunJob(sessionId) { submitRun(work) },
                persistence =
                    PendingSubmissionPersistence(
                        key = PendingRunSubmissionKey(requestEndpoint, sessionId),
                        recoveryKey = recoverySessionKey,
                        knownRunIds = knownRunIds,
                        attemptId = attemptId,
                    ),
            )
        }

    private fun submissionPersisted(
        run: Run,
        persistBeforeApply: Boolean,
        recoveryEntryPersisted: Boolean,
        submissionBindingSucceeded: Boolean,
    ): Boolean {
        return run.isActive() && persistBeforeApply && recoveryEntryPersisted && submissionBindingSucceeded
    }

    private fun submissionRunsAndState(
        opened: OpenSessionUiState,
        sessionId: SessionId,
    ): Pair<List<Run>, RunSubmissionState> {
        val knownRuns =
            visibleSessionRuns(sessionId).ifEmpty {
                (opened.activeRuns + listOfNotNull(opened.latestRun)).distinctBy { it.id }
            }
        val latestRun = knownRuns.latestRun() ?: opened.latestRun
        val state =
            RunSubmissionState(
                latestRun = latestRun,
                activeRuns = knownRuns.activeRuns(),
                isSubmissionPending = opened.isSending || runJobs.containsKey(sessionId),
            )
        return knownRuns to state
    }

    private fun recordSubmissionDraft(
        sessionId: SessionId,
        input: String,
    ) {
        val draft =
            PendingDraft(
                text = input,
                revision = sessionDraftRevisions[sessionId] ?: 0L,
            )
        pendingRunDrafts[sessionId] = draft
        sessionDrafts[sessionId] = input
    }

    private fun claimAndStartRunJob(submission: PreparedRunSubmission) {
        try {
            val claimed =
                runSubmissionUncertaintyStore.add(
                    submission.persistence.key,
                    submission.persistence.knownRunIds,
                    submission.persistence.attemptId,
                )
            if (!claimed) error("A Gateway Run submission is already unresolved.")
        } catch (_: Exception) {
            abortUnclaimedRunJob(submission)
            return
        }
        submission.job.start()
    }

    private fun abortUnclaimedRunJob(submission: PreparedRunSubmission) {
        val persistence = submission.persistence
        synchronized(sessionRequestLock) {
            if (
                pendingCreateSessions[persistence.recoveryKey] ==
                PendingCreateState(persistence.knownRunIds, persistence.attemptId)
            ) {
                pendingCreateSessions.remove(persistence.recoveryKey)
                pendingCreateStarted.remove(persistence.recoveryKey)
                unresolvedSubmissionSessions += persistence.key.sessionId
                pendingRunDrafts.remove(persistence.key.sessionId)?.let { draft ->
                    uncertainSendDrafts[persistence.key.sessionId] = draft
                }
                sessionSendErrors[persistence.key.sessionId] = MessageSendErrorCategory.UNCERTAIN
                val (current, opened) = openedSessionFor(persistence.key.sessionId) ?: return@synchronized
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
        synchronized(sessionRequestLock) {
            if (runJobs[persistence.key.sessionId] === submission.job) {
                runJobs.remove(persistence.key.sessionId)
            }
        }
        submission.job.cancel()
    }

    private suspend fun submitRun(work: RunSubmissionWork) {
        if (
            !markPendingCreateStarted(
                work.recoverySessionKey,
                work.pendingCreate,
                work.requestConnectionGeneration,
            )
        ) {
            return
        }
        try {
            val run =
                withContext(NonCancellable) {
                    withTimeout(sendTimeoutMillis) {
                        runInterruptible {
                            SubmitMessage(work.gateway).execute(work.sessionId, work.input)
                        }
                    }
                }
            val applied = completeSuccessfulSubmission(work, run)
            if (applied) {
                if (!run.isActive()) {
                    reconcileInactiveSubmittedRun(work.sessionId, run.id, work.requestConnectionGeneration)
                }
                onRunSubmissionCompleted?.invoke()
            }
        } catch (_: TimeoutCancellationException) {
            if (reportUncertainRunSubmission(work)) {
                onRunSubmissionCompleted?.invoke()
            }
        } catch (error: CancellationException) {
            runCatching {
                runSubmissionUncertaintyStore.markAmbiguous(
                    PendingRunSubmissionKey(work.requestEndpoint, work.sessionId),
                    work.attemptId,
                )
            }
            throw error
        } catch (_: GatewayException) {
            if (reportUncertainRunSubmission(work, MessageSendErrorCategory.GATEWAY_REQUEST_FAILED)) {
                onRunSubmissionCompleted?.invoke()
            }
        } catch (_: Exception) {
            if (reportUncertainRunSubmission(work, MessageSendErrorCategory.GATEWAY_REQUEST_FAILED)) {
                onRunSubmissionCompleted?.invoke()
            }
        } finally {
            forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
            onRunSubmissionSettled?.invoke()
        }
    }

    private fun completeSuccessfulSubmission(
        work: RunSubmissionWork,
        run: Run,
    ): Boolean {
        if (work.rejectRunId != null && run.id == work.rejectRunId) {
            throw GatewayException(
                GatewayErrorCategory.INVALID_RESPONSE,
                "Retry response reused the failed Run identity.",
            )
        }
        synchronized(sessionRequestLock) {
            if (connectionGeneration == work.requestConnectionGeneration) {
                submittedRunInputs[run.id] = work.input
            }
        }
        val applied =
            applySubmittedRun(
                sessionId = work.sessionId,
                run = run,
                requestConnectionGeneration = work.requestConnectionGeneration,
                requestEndpoint = work.requestEndpoint,
                attemptId = work.attemptId,
            )
        forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
        recordDiagnostic(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.SUCCEEDED)
        return applied
    }

    private suspend fun reportUncertainRunSubmission(
        work: RunSubmissionWork,
        uncertaintyErrorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
    ): Boolean {
        recordDiagnostic(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.UNCERTAIN)
        runCatching {
            runSubmissionUncertaintyStore.markAmbiguous(
                PendingRunSubmissionKey(work.requestEndpoint, work.sessionId),
                work.attemptId,
            )
        }
        forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
        return reconcileTimedOutSend(
            TimedOutSendReconciliationRequest(
                sessionId = work.sessionId,
                connectionGeneration = work.requestConnectionGeneration,
                knownRunIds = work.knownRunIds,
                attemptId = work.attemptId,
                uncertaintyErrorCategory = uncertaintyErrorCategory,
            ),
        )
    }

    private fun reconcileInactiveSubmittedRun(
        sessionId: SessionId,
        runId: RunId,
        requestConnectionGeneration: Long,
    ) {
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
                runId = runId,
                requestConnectionGeneration = requestConnectionGeneration,
                requestSessionGeneration = reconciliationContext.second,
                sessionGateway = reconciliationContext.first,
            )
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
                    attemptId = runSubmissionUncertaintyStore.attemptId(key) ?: LEGACY_ATTEMPT_ID,
                    draft =
                        PendingDraft(
                            text = sessionDrafts[sessionId].orEmpty(),
                            revision = sessionDraftRevisions[sessionId] ?: 0L,
                        ),
                ).also { pendingTimedOutSends[sessionId] = it }
            }
        }
    }

    private suspend fun reconcileTimedOutSend(request: TimedOutSendReconciliationRequest): Boolean {
        var requestEndpoint = ""
        val connectionIsCurrent =
            synchronized(sessionRequestLock) {
                if (connectionGeneration != request.connectionGeneration) {
                    false
                } else {
                    requestEndpoint = _uiState.value.endpoint
                    pendingTimedOutSends[request.sessionId] =
                        TimedOutSendRecovery(
                            knownRunIds = request.knownRunIds,
                            attemptId = request.attemptId,
                            draft =
                                pendingRunDrafts[request.sessionId]
                                    ?: PendingDraft(
                                        text = sessionDrafts[request.sessionId].orEmpty(),
                                        revision = sessionDraftRevisions[request.sessionId] ?: 0L,
                                    ),
                            errorCategory = request.uncertaintyErrorCategory,
                        )
                    true
                }
            }
        if (!connectionIsCurrent) return false
        val currentSessionGeneration =
            synchronized(sessionRequestLock) {
                _uiState.value.sessionList?.openedSession
                    ?.takeIf { it.session.id == request.sessionId }
                    ?.let { sessionRequestGeneration }
            }
        return currentSessionGeneration?.let { generation ->
            reconcileTimedOutSendNow(
                sessionId = request.sessionId,
                request = ReconciliationRequest(request.connectionGeneration, generation),
                knownRunIds = request.knownRunIds,
                resolveWhenNoNewRun = request.resolveWhenNoNewRun,
                requestEndpoint = requestEndpoint,
            )
        } ?: true
    }

    private suspend fun reconcileTimedOutSendNow(
        sessionId: SessionId,
        request: ReconciliationRequest,
        knownRunIds: Set<RunId>,
        resolveWhenNoNewRun: Boolean = false,
        requestEndpoint: String = _uiState.value.endpoint,
    ): Boolean {
        if (!awaitReconciliationClaim(sessionId, request.connectionGeneration, request.sessionGeneration)) return false
        return try {
            val gateways = timedOutSendGateways(request.connectionGeneration, request.sessionGeneration)
            if (gateways == null) {
                showTimedOutSendFailure(sessionId, request.connectionGeneration, request.sessionGeneration)
                true
            } else {
                runTimedOutSendReconciliation(
                    TimedOutSendAttempt(
                        sessionId = sessionId,
                        gateways = gateways,
                        knownRunIds = knownRunIds,
                        requestEndpoint = requestEndpoint,
                        request = request,
                        resolveWhenNoNewRun = resolveWhenNoNewRun,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showTimedOutSendFailure(sessionId, request.connectionGeneration, request.sessionGeneration)
            true
        } catch (_: Exception) {
            showTimedOutSendFailure(sessionId, request.connectionGeneration, request.sessionGeneration)
            true
        } finally {
            finishReconciliation(sessionId, request.connectionGeneration, request.sessionGeneration)
        }
    }

    private suspend fun awaitReconciliationClaim(
        sessionId: SessionId,
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ): Boolean {
        var began = false
        var attempt = 0
        while (!began && attempt < RECOVERY_CLAIM_ATTEMPTS) {
            val requestIsCurrent =
                synchronized(sessionRequestLock) {
                    connectionGeneration == requestConnectionGeneration &&
                        sessionRequestGeneration == requestSessionGeneration
                }
            if (!requestIsCurrent) return false
            if (beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) {
                began = true
            } else {
                delay(RECOVERY_CLAIM_RETRY_MILLIS)
                attempt += 1
            }
        }
        return began
    }

    private fun timedOutSendGateways(
        requestConnectionGeneration: Long,
        requestSessionGeneration: Long,
    ): Pair<SessionGatewayPort, RunGatewayPort>? =
        synchronized(sessionRequestLock) {
            val sessionGateway = sessionGateway
            val runGateway = runGateway
            if (
                connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration
            ) {
                null
            } else if (sessionGateway == null || runGateway == null) {
                null
            } else {
                sessionGateway to runGateway
            }
        }

    private fun runTimedOutSendReconciliation(attempt: TimedOutSendAttempt): Boolean {
        val result =
            ReconcileSession(attempt.gateways.first, attempt.gateways.second)
                .execute(attempt.sessionId, attempt.knownRunIds)
        val boundRunId =
            runSubmissionUncertaintyStore.boundRunId(
                PendingRunSubmissionKey(attempt.requestEndpoint, attempt.sessionId),
            )
        val boundRunReconciliation =
            boundRunId
                ?.takeUnless { runId -> result.discoveredRuns.any { it.id == runId } }
                ?.let { runId ->
                    runCatching {
                        ReconcileRun(attempt.gateways.second, attempt.gateways.first)
                            .execute(runId, attempt.sessionId)
                    }.getOrNull()
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
                attempt.sessionId,
                attempt.request.connectionGeneration,
                attempt.request.sessionGeneration,
                reconciledResult,
                attempt.resolveWhenNoNewRun,
            )
        if (outcome.applied) {
            outcome.runToPersist?.let { entry ->
                persistOrQueueRecoveryEntry(attempt.requestEndpoint, entry)
            }
            outcome.runToRemove?.let { entry ->
                removeRecoveryEntry(attempt.requestEndpoint, entry)
            }
            outcome.runToObserve?.let { run ->
                startRunObservation(
                    sessionId = attempt.sessionId,
                    run = run,
                    expectedConnectionGeneration = attempt.request.connectionGeneration,
                    expectedSessionGeneration = attempt.request.sessionGeneration,
                )
            }
        }
        return outcome.applied
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
            val current =
                _uiState.value.sessionList
                    ?: return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            val opened =
                current.openedSession?.takeIf { it.session.id == sessionId }
                    ?: return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            val evaluation =
                evaluateTimedOutSendUncertainty(sessionId, reconciliation, resolveWhenNoNewRun)
                    ?: return@synchronized TimedOutSendReconciliationOutcome(applied = false)
            authoritativeSessionRuns[sessionId] =
                mergeRuns(evaluation.retainedAuthoritativeRuns, evaluation.authoritativeRuns)
            evaluation.durableBoundRunId?.let { boundRunId -> uncertainSubmissionRunIds[sessionId] = boundRunId }
            if (evaluation.discoveredUnattributedRun) {
                ambiguousSubmissionSessions += sessionId
                runCatching {
                    runSubmissionUncertaintyStore.markAmbiguous(evaluation.pendingKey, evaluation.attemptId)
                }
            }
            evaluation.terminalRunIds.forEach { runId -> forgetUnresolvedLocalRun(sessionId, runId) }
            if (evaluation.canClearUncertainty) {
                clearTimedOutSendUncertainty(sessionId, current, opened, evaluation, resolveWhenNoNewRun)
            } else {
                markTimedOutSendUncertain(
                    sessionId = sessionId,
                    ui =
                        TimedOutSendUiContext(
                            current = current,
                            opened = opened,
                            recoveryDraft = evaluation.recoveryDraft,
                            authoritativeMessages = evaluation.authoritativeMessages,
                        ),
                    errorCategory = evaluation.pendingRecovery?.errorCategory ?: MessageSendErrorCategory.UNCERTAIN,
                )
            }
            pendingTimedOutSends.remove(sessionId)
            TimedOutSendReconciliationOutcome(
                applied = true,
                runToObserve = evaluation.boundSubmissionRun?.takeIf(Run::isActive),
                runToPersist =
                    evaluation.discoveredLocalRun
                        ?.takeIf(Run::isActive)
                        ?.let { RunRecoveryEntry(sessionId, it.id) },
                runToRemove =
                    evaluation.submissionRun
                        ?.takeIf { evaluation.canClearUncertainty && !it.isActive() }
                        ?.let { RunRecoveryEntry(sessionId, it.id) },
            )
        }

    private fun evaluateTimedOutSendUncertainty(
        sessionId: SessionId,
        reconciliation: SessionReconciliation,
        resolveWhenNoNewRun: Boolean,
    ): TimedOutSendUncertainty? {
        val pendingRecovery = pendingTimedOutSends[sessionId]
        val pendingKey = pendingRunSubmissionKey(sessionId)
        val uncertaintySnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
        val attemptId =
            pendingRecovery?.attemptId ?: uncertaintySnapshot?.attemptId ?: LEGACY_ATTEMPT_ID
        val authoritativeRuns = authoritativeRunsFor(reconciliation)
        val retainedAuthoritativeRuns = retainedAuthoritativeRuns(sessionId, authoritativeRuns)
        val belongsToThisSubmission =
            uncertaintyBelongsToSubmission(uncertaintySnapshot, pendingRecovery, attemptId)
        val durableBoundRunId =
            uncertaintySnapshot
                ?.boundRunId
                ?.takeIf { belongsToThisSubmission }
        val localBoundRunId = localBoundRunIdFor(sessionId, attemptId, uncertaintySnapshot)
        val boundSubmissionRun = boundSubmissionRun(sessionId, durableBoundRunId, localBoundRunId, reconciliation)
        val discoveredLocalRun: Run? = null
        val discoveredUnattributedRun =
            discoveredUnattributedRun(boundSubmissionRun, reconciliation, belongsToThisSubmission)
        val submissionRun = boundSubmissionRun ?: discoveredLocalRun
        val terminalRunIds = confirmedTerminalRunIds(reconciliation, submissionRun)
        val recoveryAllowsNoNewRun =
            recoveryStoreAllowsNoNewRun(uncertaintySnapshot, belongsToThisSubmission)
        val noNewRunConfirms =
            noNewRunConfirmsNoSubmission(
                sessionId = sessionId,
                resolveWhenNoNewRun = resolveWhenNoNewRun,
                discoveredUnattributedRun = discoveredUnattributedRun,
                recoveryStoreAllowsNoNewRun = recoveryAllowsNoNewRun,
            )
        val submissionConfirms = submissionConfirmed(submissionRun, belongsToThisSubmission)
        val canResolveUncertainty = belongsToThisSubmission && (submissionConfirms || noNewRunConfirms)
        val uncertaintyRemoved = uncertaintyCanBeRemoved(pendingKey, uncertaintySnapshot, canResolveUncertainty)
        val canClearUncertainty = canResolveUncertainty && uncertaintyRemoved
        val identityIsStable =
            uncertaintyIdentityIsStable(pendingKey, uncertaintySnapshot, canClearUncertainty, belongsToThisSubmission)
        return if (identityIsStable) {
            TimedOutSendUncertainty(
                pendingKey = pendingKey,
                pendingRecovery = pendingRecovery,
                recoveryDraft = pendingRecovery?.draft,
                attemptId = attemptId,
                authoritativeMessages = reconciliation.history.toMessageUiStates(),
                retainedAuthoritativeRuns = retainedAuthoritativeRuns,
                authoritativeRuns = authoritativeRuns,
                durableBoundRunId = durableBoundRunId,
                discoveredUnattributedRun = discoveredUnattributedRun,
                boundSubmissionRun = boundSubmissionRun,
                discoveredLocalRun = discoveredLocalRun,
                submissionRun = submissionRun,
                terminalRunIds = terminalRunIds,
                canClearUncertainty = canClearUncertainty,
            )
        } else {
            null
        }
    }

    private fun authoritativeRunsFor(reconciliation: SessionReconciliation): List<Run> =
        mergeRuns(reconciliation.history.runs(), reconciliation.discoveredRuns)

    private fun retainedAuthoritativeRuns(
        sessionId: SessionId,
        incoming: List<Run>,
    ): List<Run> {
        val incomingRunIds = incoming.mapTo(mutableSetOf()) { it.id }
        return authoritativeSessionRuns[sessionId]
            .orEmpty()
            .filter { existing -> existing.id !in incomingRunIds }
    }

    private fun uncertaintyBelongsToSubmission(
        uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
        pendingRecovery: TimedOutSendRecovery?,
        attemptId: String,
    ): Boolean =
        uncertaintySnapshot == null ||
            (
                uncertaintySnapshot.attemptId == attemptId &&
                    uncertaintySnapshot.knownRunIds == pendingRecovery?.knownRunIds.orEmpty()
            )

    private fun localBoundRunIdFor(
        sessionId: SessionId,
        attemptId: String,
        uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
    ): RunId? =
        uncertainSubmissionRunIds[sessionId]
            ?.takeIf {
                uncertaintySnapshot == null && uncertainSubmissionAttemptIds[sessionId] == attemptId
            }

    private fun boundSubmissionRun(
        sessionId: SessionId,
        durableBoundRunId: RunId?,
        localBoundRunId: RunId?,
        reconciliation: SessionReconciliation,
    ): Run? =
        (durableBoundRunId ?: localBoundRunId)?.let { runId ->
            reconciliation.discoveredRuns.lastOrNull { it.id == runId }
                ?: visibleSessionRuns(sessionId).lastOrNull { it.id == runId && it.isActive() }
        }

    private fun discoveredUnattributedRun(
        boundSubmissionRun: Run?,
        reconciliation: SessionReconciliation,
        belongsToThisSubmission: Boolean,
    ): Boolean =
        boundSubmissionRun == null &&
            reconciliation.discoveredRuns.isNotEmpty() &&
            belongsToThisSubmission

    private fun confirmedTerminalRunIds(
        reconciliation: SessionReconciliation,
        submissionRun: Run?,
    ): Set<RunId> =
        reconciliation.discoveredRuns
            .filter { run ->
                decideRunReconciliation(run) ==
                    RunReconciliationDecision.CONFIRMED
            }
            .map { it.id }
            .plus(
                submissionRun
                    ?.takeIf { run -> decideRunReconciliation(run) == RunReconciliationDecision.CONFIRMED }
                    ?.id,
            )
            .filterNotNull()
            .toSet()

    private fun submissionConfirmed(
        submissionRun: Run?,
        belongsToThisSubmission: Boolean,
    ): Boolean =
        submissionRun != null &&
            decideRunReconciliation(submissionRun) == RunReconciliationDecision.CONFIRMED &&
            belongsToThisSubmission

    private fun recoveryStoreAllowsNoNewRun(
        uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
        belongsToThisSubmission: Boolean,
    ): Boolean =
        uncertaintySnapshot == null ||
            (
                belongsToThisSubmission &&
                    uncertaintySnapshot.settled &&
                    !uncertaintySnapshot.requiresRunMatch
            )

    private fun noNewRunConfirmsNoSubmission(
        sessionId: SessionId,
        resolveWhenNoNewRun: Boolean,
        discoveredUnattributedRun: Boolean,
        recoveryStoreAllowsNoNewRun: Boolean,
    ): Boolean =
        resolveWhenNoNewRun &&
            sessionId !in ambiguousSubmissionSessions &&
            !discoveredUnattributedRun &&
            recoveryStoreAllowsNoNewRun &&
            uncertainSubmissionRunIds[sessionId] == null

    private fun uncertaintyCanBeRemoved(
        pendingKey: PendingRunSubmissionKey,
        uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
        canResolveUncertainty: Boolean,
    ): Boolean =
        !canResolveUncertainty ||
            !runSubmissionUncertaintyStore.contains(pendingKey) ||
            runCatching {
                uncertaintySnapshot != null &&
                    runSubmissionUncertaintyStore.removeIfSnapshotMatches(pendingKey, uncertaintySnapshot)
            }.getOrDefault(false)

    private fun uncertaintyIdentityIsStable(
        pendingKey: PendingRunSubmissionKey,
        uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
        canClearUncertainty: Boolean,
        belongsToThisSubmission: Boolean,
    ): Boolean {
        val currentSnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
        val stable =
            if (canClearUncertainty) {
                currentSnapshot == null
            } else if (uncertaintySnapshot == null) {
                currentSnapshot == null
            } else {
                currentSnapshot?.let { current ->
                    current.attemptId == uncertaintySnapshot.attemptId &&
                        current.knownRunIds == uncertaintySnapshot.knownRunIds &&
                        current.boundRunId == uncertaintySnapshot.boundRunId
                } ?: false
            }
        return belongsToThisSubmission && stable
    }

    private fun clearTimedOutSendUncertainty(
        sessionId: SessionId,
        current: SessionListUiState,
        opened: OpenSessionUiState,
        evaluation: TimedOutSendUncertainty,
        resolveWhenNoNewRun: Boolean,
    ) {
        unresolvedSubmissionSessions.remove(sessionId)
        ambiguousSubmissionSessions.remove(sessionId)
        uncertainSubmissionRunIds.remove(sessionId)
        uncertainSubmissionAttemptIds.remove(sessionId)
        evaluation.submissionRun?.let { run -> forgetObservationState(sessionId, run.id) }
        uncertainSendDrafts.remove(sessionId)
        val clearedSendErrorCategory =
            if (resolveWhenNoNewRun) {
                MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            } else {
                evaluation.pendingRecovery?.errorCategory ?: MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }
        sessionSendErrors[sessionId] = clearedSendErrorCategory
        val knownRuns = visibleSessionRuns(sessionId)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                messages = evaluation.authoritativeMessages,
                                composerText = sessionDrafts[sessionId].orEmpty(),
                                isSending = false,
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                latestRunState =
                                    latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                                sendErrorCategory = clearedSendErrorCategory,
                                hasUnresolvedSubmission = false,
                                errorCategory = null,
                                isStale = false,
                            ),
                    ),
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
                return@synchronized
            }
            val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
            val recoveryDraft = pendingTimedOutSends[sessionId]?.draft
            markTimedOutSendUncertain(
                sessionId,
                TimedOutSendUiContext(current, opened, recoveryDraft),
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
        val (recoveryContext, claim) = claimPersistedRecoveryRun(endpoint, entry) ?: return
        val job = beginRecoveryJob(endpoint, entry, recoveryContext, claim) ?: return
        job.start()
    }

    private fun claimPersistedRecoveryRun(
        endpoint: String,
        entry: RunRecoveryEntry,
    ): Pair<RecoveryRunContext, RecoveryClaim>? =
        recoveryRunContextFor(endpoint, entry)?.let { recoveryContext ->
            claimRecoveryEntryForRun(endpoint, entry, recoveryContext.connectionGeneration)?.let { claim ->
                recoveryContext to claim
            }
        }

    private fun recoveryRunContextFor(
        endpoint: String,
        entry: RunRecoveryEntry,
    ): RecoveryRunContext? =
        synchronized(sessionRequestLock) {
            val currentSessionGateway = sessionGateway
            val currentRunGateway = runGateway
            if (
                !_uiState.value.isConnected ||
                _uiState.value.endpoint != endpoint
            ) {
                null
            } else if (currentSessionGateway == null || currentRunGateway == null) {
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
        }

    private fun claimRecoveryEntryForRun(
        endpoint: String,
        entry: RunRecoveryEntry,
        requestConnectionGeneration: Long,
    ): RecoveryClaim? =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != requestConnectionGeneration ||
                _uiState.value.endpoint != endpoint
            ) {
                null
            } else {
                claimRecoveryEntry(endpoint, entry, requestConnectionGeneration)
            }
        }

    private fun beginRecoveryJob(
        endpoint: String,
        entry: RunRecoveryEntry,
        recoveryContext: RecoveryRunContext,
        claim: RecoveryClaim,
    ): Job? =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != recoveryContext.connectionGeneration ||
                _uiState.value.endpoint != endpoint ||
                recoveryClaims[claim.key] != claim
            ) {
                return@synchronized null
            }
            rememberRecoveredRun(
                entry = entry,
                run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                updateVisibleState = false,
            )
            if (connectionRecoverySessionCounts[entry.sessionId] == null) {
                connectionRecoveryFailedSessions.remove(entry.sessionId)
            }
            connectionRecoverySessionCounts[entry.sessionId] =
                (connectionRecoverySessionCounts[entry.sessionId] ?: 0) + 1
            if (recoveryContext.updateVisibleUi) {
                markRecoveryUiRefreshing(entry)
            }
            val job = startRecoveryObservationJob(entry, recoveryContext, claim)
            connectionRecoveryJobs += job
            job
        }

    private fun markRecoveryUiRefreshing(entry: RunRecoveryEntry) {
        val (current, opened) = openedSessionFor(entry.sessionId) ?: return
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isRefreshing = true,
                                isReconciliationInProgress = true,
                            ),
                    ),
            )
    }

    private fun startRecoveryObservationJob(
        entry: RunRecoveryEntry,
        recoveryContext: RecoveryRunContext,
        claim: RecoveryClaim,
    ): Job {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                var recoveryFinished = false
                var recoveryCompleted = false
                try {
                    val recoveredRun =
                        recoverRun(
                            entry = entry,
                            context = recoveryContext,
                        )
                    recoveryFinished = true
                    recoveryCompleted = recoveredRun != null
                    recoveredRun?.takeIf(Run::isActive)?.let { run ->
                        startRunObservation(
                            sessionId = entry.sessionId,
                            run = run,
                            expectedConnectionGeneration = recoveryContext.connectionGeneration,
                            expectedSessionGeneration = recoveryContext.sessionGeneration,
                            expectedHistoryGeneration = recoveryContext.historyGeneration,
                        )
                    }
                } finally {
                    synchronized(sessionRequestLock) {
                        if (
                            connectionGeneration == recoveryContext.connectionGeneration &&
                            releaseRecoveryClaim(claim)
                        ) {
                            connectionRecoveryJobs.remove(job)
                            if (recoveryFinished) {
                                recoveryHandledEntries += claim.key
                            }
                            if (!recoveryCompleted) {
                                connectionRecoveryFailedSessions += entry.sessionId
                            }
                            releaseRecoverySessionCount(entry, recoveryContext)
                        }
                    }
                }
            }
        return job
    }

    private fun releaseRecoverySessionCount(
        entry: RunRecoveryEntry,
        recoveryContext: RecoveryRunContext,
    ) {
        val remaining = (connectionRecoverySessionCounts[entry.sessionId] ?: 1) - 1
        if (remaining > 0) {
            connectionRecoverySessionCounts[entry.sessionId] = remaining
        } else {
            connectionRecoverySessionCounts.remove(entry.sessionId)
            val recoveryFailed = connectionRecoveryFailedSessions.remove(entry.sessionId)
            if (
                recoveryContext.updateVisibleUi &&
                sessionRequestGeneration == recoveryContext.sessionGeneration
            ) {
                if (recoveryFailed) {
                    markRecoveryFailure(entry.sessionId)
                } else {
                    clearRecoveryUiIfIdle(entry.sessionId)
                }
            }
        }
    }

    private fun applySubmittedRun(
        sessionId: SessionId,
        run: Run,
        requestConnectionGeneration: Long,
        requestEndpoint: String,
        attemptId: String,
    ): Boolean {
        val preparation = prepareSubmittedRun(sessionId, run, requestConnectionGeneration, requestEndpoint, attemptId)
        val result =
            applySubmittedRunLocked(
                SubmittedRunApplication(
                    sessionId = sessionId,
                    run = run,
                    requestConnectionGeneration = requestConnectionGeneration,
                    requestEndpoint = requestEndpoint,
                    attemptId = attemptId,
                    pendingKey = preparation.pendingKey,
                    submissionBindingSucceeded = preparation.submissionBindingSucceeded,
                    recoveryPersistenceFailed = preparation.recoveryPersistenceFailed,
                ),
            )
        if (!result.applied && result.persistAfterDisconnect && !preparation.persistBeforeApply) {
            val entry = RunRecoveryEntry(sessionId = sessionId, runId = run.id)
            if (run.isActive()) {
                val persisted = persistDisconnectedRecoveryEntry(endpoint = requestEndpoint, entry = entry)
                if (persisted) {
                    runCatching { runSubmissionUncertaintyStore.remove(preparation.pendingKey, attemptId) }
                }
            } else {
                reconcilePersistedRecoveryEntryIfConnected(requestEndpoint, entry)
            }
        }
        result.observationToClose?.close()
        result.observationJobToCancel?.cancel()
        if (result.applied && result.shouldObserve) {
            startRunObservation(
                sessionId = sessionId,
                run = run,
                expectedConnectionGeneration = requestConnectionGeneration,
                expectedSessionGeneration = result.requestSessionGeneration,
            )
        }
        return result.applied
    }

    private fun prepareSubmittedRun(
        sessionId: SessionId,
        run: Run,
        requestConnectionGeneration: Long,
        requestEndpoint: String,
        attemptId: String,
    ): SubmittedRunPreparation {
        val pendingKey = PendingRunSubmissionKey(requestEndpoint, sessionId)
        val submissionBindingSucceeded = bindSubmittedRun(pendingKey, run.id, attemptId)
        val persistBeforeApply = persistBeforeApply(run, requestConnectionGeneration)
        val recoveryEntryPersisted =
            persistBeforeApply &&
                persistOrQueueRecoveryEntry(
                    requestEndpoint,
                    RunRecoveryEntry(sessionId = sessionId, runId = run.id),
                )
        val recoveryPersistenceFailed =
            if (submissionPersisted(run, persistBeforeApply, recoveryEntryPersisted, submissionBindingSucceeded)) {
                !runCatching { runSubmissionUncertaintyStore.remove(pendingKey, attemptId) }.getOrDefault(false)
            } else if (run.isActive()) {
                persistBeforeApply && (!recoveryEntryPersisted || !submissionBindingSucceeded)
            } else {
                !submissionBindingSucceeded
            }
        return SubmittedRunPreparation(
            pendingKey = pendingKey,
            submissionBindingSucceeded = submissionBindingSucceeded,
            persistBeforeApply = persistBeforeApply,
            recoveryPersistenceFailed = recoveryPersistenceFailed,
        )
    }

    private fun bindSubmittedRun(
        pendingKey: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String,
    ): Boolean = runCatching { runSubmissionUncertaintyStore.bindRun(pendingKey, runId, attemptId) }.getOrDefault(false)

    private fun persistBeforeApply(
        run: Run,
        requestConnectionGeneration: Long,
    ): Boolean =
        run.isActive() &&
            synchronized(sessionRequestLock) {
                connectionGeneration == requestConnectionGeneration
            }

    private fun applySubmittedRunLocked(application: SubmittedRunApplication): SubmittedRunApplicationResult =
        synchronized(sessionRequestLock) {
            if (connectionGeneration != application.requestConnectionGeneration) {
                if (application.run.isActive()) {
                    rememberUnresolvedLocalRun(
                        application.requestEndpoint,
                        application.sessionId,
                        application.run.id,
                        application.attemptId,
                    )
                }
                return@synchronized SubmittedRunApplicationResult(
                    applied = false,
                    persistAfterDisconnect = application.run.isActive(),
                )
            }
            val shouldObserve = application.run.isActive()
            recordSubmittedRunState(application)
            val observationState = markSubmittedRunObservationState(application, shouldObserve)
            val releases = updateSubmittedRunUi(application, observationState)
            SubmittedRunApplicationResult(
                applied = true,
                requestSessionGeneration = sessionRequestGeneration,
                shouldObserve = shouldObserve,
                observationJobToCancel = releases.job,
                observationToClose = releases.observation,
            )
        }

    private fun recordSubmittedRunState(application: SubmittedRunApplication) {
        if (application.recoveryPersistenceFailed) {
            runCatching {
                runSubmissionUncertaintyStore.markAmbiguous(application.pendingKey, application.attemptId)
            }
        }
        if (!application.submissionBindingSucceeded) {
            rememberUnresolvedLocalRun(
                application.requestEndpoint,
                application.sessionId,
                application.run.id,
                application.attemptId,
            )
            unresolvedSubmissionSessions += application.sessionId
        } else {
            uncertainSubmissionRunIds[application.sessionId] = application.run.id
            uncertainSubmissionAttemptIds[application.sessionId] = application.attemptId
        }
        val submittedDraft =
            if (application.submissionBindingSucceeded) pendingRunDrafts.remove(application.sessionId) else null
        if (!application.submissionBindingSucceeded || application.recoveryPersistenceFailed) {
            if (application.submissionBindingSucceeded) {
                submittedDraft?.let { uncertainSendDrafts[application.sessionId] = it }
            }
            sessionSendErrors[application.sessionId] = MessageSendErrorCategory.UNCERTAIN
        } else if (
            submittedDraft != null &&
            sessionDraftRevisions[application.sessionId] == submittedDraft.revision
        ) {
            sessionSendErrors.remove(application.sessionId)
            sessionDrafts.remove(application.sessionId)
        } else {
            sessionSendErrors.remove(application.sessionId)
        }
        sessionRuns[application.sessionId] =
            (sessionRuns[application.sessionId].orEmpty().filterNot { it.id == application.run.id } + application.run)
        val recoveryKey = RecoverySessionKey(application.requestEndpoint, application.sessionId)
        pendingCreateSessions[recoveryKey]
            ?.takeIf { it.attemptId == application.attemptId }
            ?.let { forgetPendingCreate(recoveryKey, it) }
    }

    private fun markSubmittedRunObservationState(
        application: SubmittedRunApplication,
        shouldObserve: Boolean,
    ): RunObservationState {
        val observationState =
            if (shouldObserve && !application.recoveryPersistenceFailed) {
                observationStateFor(application.sessionId, application.run.id)
                    ?: RunEventStateTransition.initial(application.run)
            } else {
                uncertainObservationState(
                    application.run,
                    observationStateFor(application.sessionId, application.run.id),
                )
            }
        rememberObservationState(observationState)
        return observationState
    }

    private fun updateSubmittedRunUi(
        application: SubmittedRunApplication,
        observationState: RunObservationState,
    ): ReconciledObservationRelease {
        val current = _uiState.value.sessionList
        val opened = current?.openedSession?.takeIf { it.session.id == application.sessionId }
        if (current == null || opened == null) {
            return ReconciledObservationRelease()
        }
        val jobToCancel = runObservationJobs[application.sessionId]
        val observationToClose = runObservations.remove(application.sessionId)
        runObservationRunIds.remove(application.sessionId)
        val knownRuns = visibleSessionRuns(application.sessionId)
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                composerText = sessionDrafts[application.sessionId].orEmpty(),
                                latestRun = knownRuns.latestRun() ?: application.run,
                                activeRuns = knownRuns.activeRuns(),
                                isSending = false,
                                sendErrorCategory =
                                    if (application.recoveryPersistenceFailed) {
                                        MessageSendErrorCategory.UNCERTAIN
                                    } else {
                                        null
                                    },
                                hasUnresolvedSubmission = hasUnresolvedSubmission(application.sessionId),
                                latestRunState = observationState.state,
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(knownRuns, observationState.state),
                                activeResponse = observedMessageUiState(observationState),
                                isReconciliationInProgress = !application.run.isActive(),
                                isStale = !application.run.isActive() || application.recoveryPersistenceFailed,
                            ),
                    ),
            )
        return ReconciledObservationRelease(job = jobToCancel, observation = observationToClose)
    }

    private fun startRunObservation(
        sessionId: SessionId,
        run: Run,
        expectedConnectionGeneration: Long? = null,
        expectedSessionGeneration: Long? = null,
        expectedHistoryGeneration: Long? = null,
    ) {
        val start =
            prepareRunObservation(
                sessionId = sessionId,
                run = run,
                expectedConnectionGeneration = expectedConnectionGeneration,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedHistoryGeneration = expectedHistoryGeneration,
            )
        start.observationToClose?.close()
        start.observerJobToCancel?.cancel()
        if (start.handoffRequested) return
        val observationJob = start.jobToStart ?: return
        observationJob.invokeOnCompletion {
            restartObservationAfterCompletion(observationJob, sessionId)?.let { request ->
                startRunObservation(
                    sessionId = sessionId,
                    run = request.run,
                    expectedConnectionGeneration = request.connectionGeneration,
                    expectedSessionGeneration = request.sessionGeneration,
                )
            }
        }
        observationJob.start()
    }

    private fun prepareRunObservation(
        sessionId: SessionId,
        run: Run,
        expectedConnectionGeneration: Long?,
        expectedSessionGeneration: Long?,
        expectedHistoryGeneration: Long?,
    ): RunObservationStart =
        synchronized(sessionRequestLock) {
            if (
                !observationStartIsCurrent(
                    sessionId = sessionId,
                    expectedConnectionGeneration = expectedConnectionGeneration,
                    expectedSessionGeneration = expectedSessionGeneration,
                    expectedHistoryGeneration = expectedHistoryGeneration,
                )
            ) {
                return@synchronized RunObservationStart()
            }
            val current = _uiState.value.sessionList ?: return@synchronized RunObservationStart()
            if (!canStartObserving(sessionId, run, current.openedSession)) {
                return@synchronized RunObservationStart()
            }
            val currentRun = visibleSessionRuns(sessionId).lastOrNull { it.id == run.id }
            val currentAuthoritativeRun = authoritativeSessionRuns[sessionId]?.lastOrNull { it.id == run.id }
            val currentObservationState = observationStateFor(sessionId, run.id)
            if (
                currentRun?.isActive() == false ||
                currentAuthoritativeRun?.isActive() == false ||
                currentObservationState?.state?.isTerminal() == true
            ) {
                return@synchronized RunObservationStart()
            }
            val startRequest =
                ObserverStartRequest(run, connectionGeneration, sessionRequestGeneration)
            val existingJob = runObservationJobs[sessionId]
            if (existingJob != null) {
                startRunObservationHandoff(sessionId, run, startRequest, existingJob)
            } else {
                startNewRunObservation(sessionId, run, current)
            }
        }

    private fun observationStartIsCurrent(
        sessionId: SessionId,
        expectedConnectionGeneration: Long?,
        expectedSessionGeneration: Long?,
        expectedHistoryGeneration: Long?,
    ): Boolean {
        val connectionMatches =
            expectedConnectionGeneration == null || expectedConnectionGeneration == connectionGeneration
        val sessionMatches =
            expectedSessionGeneration == null || expectedSessionGeneration == sessionRequestGeneration
        val historyMatches =
            expectedHistoryGeneration == null ||
                expectedHistoryGeneration == (authoritativeSessionHistoryGenerations[sessionId] ?: 0L)
        return connectionMatches && sessionMatches && historyMatches
    }

    private fun canStartObserving(
        sessionId: SessionId,
        run: Run,
        opened: OpenSessionUiState?,
    ): Boolean {
        val scopeIsActive = scope.coroutineContext[Job]?.isActive != false
        val gatewaysReady = sessionGateway != null && runGateway != null
        return opened != null &&
            opened.session.id == sessionId &&
            run.isActive() &&
            scopeIsActive &&
            gatewaysReady
    }

    private fun startRunObservationHandoff(
        sessionId: SessionId,
        run: Run,
        startRequest: ObserverStartRequest,
        existingJob: Job,
    ): RunObservationStart {
        var observerJobToCancel: Job? = null
        var observationToClose: RunEventObservation? = null
        if (runObservationRunIds[sessionId] != run.id || existingJob.isCancelled) {
            pendingRunObservationRequests[sessionId] = startRequest
        }
        if (runObservationRunIds[sessionId] != run.id) {
            observerJobToCancel = existingJob
            observationToClose = runObservations.remove(sessionId)
            runObservationRunIds.remove(sessionId)
        }
        return RunObservationStart(
            observerJobToCancel = observerJobToCancel,
            observationToClose = observationToClose,
            handoffRequested = true,
        )
    }

    private fun startNewRunObservation(
        sessionId: SessionId,
        run: Run,
        current: SessionListUiState,
    ): RunObservationStart {
        val opened = current.openedSession ?: return RunObservationStart()
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
            observedMessageUiState(latestObservation)
                ?: state
                    .takeIf { latestRun == null || latestRun.id == run.id }
                    ?.let { observedMessageUiState(it) }
        _uiState.value =
            _uiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                latestRunState = latestState,
                                latestRunRetryAvailable = latestRunRetryAvailable(knownRuns, latestState),
                                activeResponse = latestResponse,
                            ),
                    ),
            )
        val observationJob =
            scope.launch(start = CoroutineStart.LAZY) {
                observeRun(
                    sessionId = sessionId,
                    run = run,
                )
            }
        runObservationJobs[sessionId] = observationJob
        runObservationRunIds[sessionId] = run.id
        return RunObservationStart(jobToStart = observationJob)
    }

    private fun restartObservationAfterCompletion(
        observationJob: Job,
        sessionId: SessionId,
    ): ObserverStartRequest? =
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
                nextRun?.let { run -> queuedObservationRestart(observationJob, sessionId, pendingRequest, run) }
            }
        }

    private fun queuedObservationRestart(
        observationJob: Job,
        sessionId: SessionId,
        pendingRequest: ObserverStartRequest?,
        nextRun: Run,
    ): ObserverStartRequest? {
        val restartRequested = observationJob.isCancelled || pendingRequest != null
        val sessionIsOpen = _uiState.value.sessionList?.openedSession?.session?.id == sessionId
        val gatewaysReady = runGateway != null && sessionGateway != null
        return if (restartRequested && sessionIsOpen && gatewaysReady) {
            ObserverStartRequest(nextRun, connectionGeneration, sessionRequestGeneration)
        } else {
            null
        }
    }

    private suspend fun observeRun(
        sessionId: SessionId,
        run: Run,
    ) {
        val observationJob = currentCoroutineContext()[Job] ?: return
        val gateway = observationGatewayFor(sessionId) ?: return
        var observation: RunEventObservation? = null
        var lateObservation: RunEventObservation? = null
        var shouldReconcile = false
        try {
            currentCoroutineContext().ensureActive()
            val activeObservation =
                runInterruptible {
                    ObserveRun(gateway).execute(run.id).also { lateObservation = it }
                }
            observation = activeObservation
            if (registerActiveObservation(sessionId, run, observationJob, activeObservation)) {
                drainObservedEvents(sessionId, run, observationJob, activeObservation)
                shouldReconcile = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (currentCoroutineContext()[Job]?.isActive == true) {
                markRunObservationUncertain(sessionId, run.id, observationJob)
                shouldReconcile = true
            }
        } finally {
            closeObservation(sessionId, observation, lateObservation)
        }
        reconcileObservationOutcome(sessionId, run, observationJob, shouldReconcile)
    }

    private fun observationGatewayFor(sessionId: SessionId): RunGatewayPort? =
        synchronized(sessionRequestLock) {
            runGateway?.takeIf {
                _uiState.value.sessionList?.openedSession?.session?.id == sessionId
            }
        }

    private fun isCurrentObservationJob(
        sessionId: SessionId,
        runId: RunId,
        observationJob: Job,
    ): Boolean {
        val jobActive = observationJob.isActive
        val sessionIsOpen = _uiState.value.sessionList?.openedSession?.session?.id == sessionId
        return jobActive &&
            sessionIsOpen &&
            runObservationJobs[sessionId] === observationJob &&
            runObservationRunIds[sessionId] == runId
    }

    private fun registerActiveObservation(
        sessionId: SessionId,
        run: Run,
        observationJob: Job,
        activeObservation: RunEventObservation,
    ): Boolean =
        synchronized(sessionRequestLock) {
            if (isCurrentObservationJob(sessionId, run.id, observationJob)) {
                runObservations[sessionId] = activeObservation
                true
            } else {
                false
            }
        }

    private suspend fun drainObservedEvents(
        sessionId: SessionId,
        run: Run,
        observationJob: Job,
        activeObservation: RunEventObservation,
    ) {
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
    }

    private fun closeObservation(
        sessionId: SessionId,
        observation: RunEventObservation?,
        lateObservation: RunEventObservation?,
    ) {
        if (observation == null) {
            lateObservation?.close()
        } else {
            observation.close()
        }
        synchronized(sessionRequestLock) {
            if (runObservations[sessionId] === observation) {
                runObservations.remove(sessionId)
            }
        }
    }

    private suspend fun reconcileObservationOutcome(
        sessionId: SessionId,
        run: Run,
        observationJob: Job,
        shouldReconcile: Boolean,
    ) {
        val reconciliationRequest =
            if (shouldReconcile) {
                currentObservationReconciliationRequest(sessionId, run.id, observationJob)
            } else {
                null
            }
        if (reconciliationRequest != null) {
            currentCoroutineContext().ensureActive()
            val gateway = reconciliationGateway(reconciliationRequest)
            if (gateway != null) {
                reconcileRun(
                    sessionId = sessionId,
                    runId = run.id,
                    requestConnectionGeneration = reconciliationRequest.connectionGeneration,
                    requestSessionGeneration = reconciliationRequest.sessionGeneration,
                    sessionGateway = gateway,
                )
            }
        }
    }

    private fun reconciliationGateway(request: ReconciliationRequest): SessionGatewayPort? =
        synchronized(sessionRequestLock) {
            sessionGateway?.takeIf {
                connectionGeneration == request.connectionGeneration &&
                    sessionRequestGeneration == request.sessionGeneration
            }
        }

    private fun currentObservationReconciliationRequest(
        sessionId: SessionId,
        runId: RunId,
        observationJob: Job,
    ): ReconciliationRequest? =
        synchronized(sessionRequestLock) {
            val observationIsCurrent = isCurrentObservationJob(sessionId, runId, observationJob)
            if (observationIsCurrent && observationStateFor(sessionId, runId) != null) {
                ReconciliationRequest(connectionGeneration, sessionRequestGeneration)
            } else {
                null
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
            if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != event.runId) {
                return@synchronized
            }
            val previous = observationStateFor(sessionId, event.runId) ?: return@synchronized
            val next = RunEventStateTransition.apply(previous, event)
            rememberObservationState(next)
            if (next.state != previous.state) {
                postTerminalRunStatusNotificationOnce(next.run, next.state)
            }
            applyRunEventState(sessionId, event, next)
        }
    }

    private fun applyRunEventState(
        sessionId: SessionId,
        event: RunEvent,
        next: RunObservationState,
    ) {
        val submissionAwaitingConfirmation = recordRunEventSubmissionState(sessionId, event, next)
        recordRunEventRunState(sessionId, event, next)
        updateRunEventSessionUi(sessionId, next, submissionAwaitingConfirmation)
    }

    private fun recordRunEventSubmissionState(
        sessionId: SessionId,
        event: RunEvent,
        next: RunObservationState,
    ): Boolean {
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
        return submissionAwaitingConfirmation
    }

    private fun recordRunEventRunState(
        sessionId: SessionId,
        event: RunEvent,
        next: RunObservationState,
    ) {
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
    }

    private fun updateRunEventSessionUi(
        sessionId: SessionId,
        next: RunObservationState,
        submissionAwaitingConfirmation: Boolean,
    ) {
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
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(knownRuns, latestObservation?.state ?: next.state),
                                activeResponse =
                                    observedMessageUiState(latestObservation) ?: observedMessageUiState(next),
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

    private fun markRunObservationUncertain(
        sessionId: SessionId,
        runId: RunId,
        observationJob: Job,
    ) {
        synchronized(sessionRequestLock) {
            if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != runId) {
                return@synchronized
            }
            val previous = observationStateFor(sessionId, runId) ?: return@synchronized
            if (previous.state.isTerminal() || !previous.run.isActive()) return@synchronized
            val next = RunEventStateTransition.interrupted(previous)
            rememberObservationState(next)
            val current = _uiState.value.sessionList ?: return@synchronized
            val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return@synchronized
            val knownRuns = visibleSessionRuns(sessionId)
            val latestRun = knownRuns.latestRun()
            val latestObservation = latestObservationState(sessionId, knownRuns)
            val latestState =
                latestObservation?.state
                    ?: next.state.takeIf { latestRun?.id == runId }
                    ?: latestRun?.toRunPresentationState()
            val latestResponse =
                observedMessageUiState(latestObservation)
                    ?: next.takeIf { latestRun?.id == runId }?.let { observedMessageUiState(it) }
            _uiState.value =
                _uiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    latestRunState = latestState,
                                    latestRunRetryAvailable = latestRunRetryAvailable(knownRuns, latestState),
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
        locallyOwnedTerminalStates.forEach { state ->
            rememberObservationState(uncertainObservationState(state.run, state))
        }
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
            val runIdToReconcile =
                openedSession.history.latestRun()?.id ?: sessionRuns[sessionId].orEmpty().latestRun()?.id
            runIdToReconcile?.takeUnless {
                hasUnresolvedSubmission(sessionId) && !hasActiveLocalRun
            }
        }

    private fun recoverySessionKey(
        sessionId: SessionId,
        endpoint: String = _uiState.value.endpoint,
    ): RecoverySessionKey = RecoverySessionKey(endpoint, sessionId)

    private fun rememberUnresolvedSessionMutations(endpoint: String) {
        if (endpoint.isBlank()) return
        _uiState.value.sessionList?.sessionMutations?.forEach { (sessionId, mutation) ->
            val action = mutation.pendingAction ?: return@forEach
            unresolvedSessionMutations[RecoverySessionKey(endpoint, sessionId)] =
                mutation.copy(
                    rename =
                        mutation.rename?.copy(
                            isSubmitting = false,
                            errorCategory = SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                        ),
                    delete = mutation.delete?.copy(isSubmitting = false),
                    pendingAction = null,
                    errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                    retryAction = action,
                )
        }
    }

    private fun restoreUnresolvedSessionMutations(
        mutations: Map<SessionId, SessionMutationUiState>,
    ): Map<SessionId, SessionMutationUiState> {
        val endpoint = _uiState.value.endpoint
        return mutations +
            unresolvedSessionMutations
                .filterKeys { it.endpoint == endpoint }
                .mapKeys { (key, _) -> key.sessionId }
    }

    private fun retainSessionMutation(
        mutations: Map<SessionId, SessionMutationUiState>,
        sessionId: SessionId,
    ): Map<SessionId, SessionMutationUiState> {
        val retained = retainRenameDraft(mutations, sessionId)
        return unresolvedSessionMutations[recoverySessionKey(sessionId)]?.let { mutation ->
            retained + (sessionId to mutation)
        } ?: retained
    }

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
        unresolvedLocalRunAttempts[key]?.remove(runId)
        if (unresolvedLocalRunIds[key].isNullOrEmpty()) {
            unresolvedLocalRunIds.remove(key)
        }
        if (unresolvedLocalRunAttempts[key].isNullOrEmpty()) {
            unresolvedLocalRunAttempts.remove(key)
        }
    }

    private fun rememberUnresolvedLocalRun(
        endpoint: String,
        sessionId: SessionId,
        runId: RunId,
        attemptId: String,
    ) {
        val key = RecoverySessionKey(endpoint, sessionId)
        unresolvedLocalRunIds.getOrPut(key, ::mutableSetOf).add(runId)
        unresolvedLocalRunAttempts.getOrPut(key, ::mutableMapOf)[runId] = attemptId
    }

    private fun markPendingCreateStarted(
        key: RecoverySessionKey,
        pendingCreate: PendingCreateState,
        expectedConnectionGeneration: Long,
    ): Boolean =
        synchronized(sessionRequestLock) {
            if (
                connectionGeneration != expectedConnectionGeneration ||
                pendingCreateSessions[key] != pendingCreate
            ) {
                false
            } else {
                pendingCreateStarted += key
                true
            }
        }

    private fun forgetPendingCreate(
        key: RecoverySessionKey,
        pendingCreate: PendingCreateState,
    ) {
        synchronized(sessionRequestLock) {
            if (pendingCreateSessions[key] == pendingCreate) {
                pendingCreateSessions.remove(key)
                pendingCreateStarted.remove(key)
            }
        }
    }

    private fun observedMessageUiState(observation: RunObservationState?): SessionMessageUiState? =
        observation?.let { current ->
            current.toSessionMessageUiState(retryInput = submittedRunInputs[current.run.id])
        }

    private fun SessionHistory.toMessageUiStates(): List<SessionMessageUiState> {
        val history = this
        return history.messages.map { message ->
            val original =
                message.runId?.let { id ->
                    submittedRunInputs[id]
                        ?: history.messages
                            .lastOrNull { it.runId == id && it.role == "user" }
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                }
            message.toSessionMessageUiState(retryInput = original)
        }.chronological()
    }
}

private data class OpenSessionOverlay(
    val composerText: String = "",
    val sendErrorCategory: MessageSendErrorCategory? = null,
    val hasUnresolvedSubmission: Boolean = false,
    val latestRun: Run? = null,
    val activeRuns: List<Run>? = null,
    val isSending: Boolean = false,
    val latestRunState: RunPresentationState? = null,
    val latestRunRetryAvailable: Boolean = false,
    val activeResponse: SessionMessageUiState? = null,
    val isRefreshing: Boolean = false,
    val messages: List<SessionMessageUiState>? = null,
)

private fun OpenedSession.toOpenSessionUiState(overlay: OpenSessionOverlay = OpenSessionOverlay()): OpenSessionUiState =
    OpenSessionUiState(
        session = session.toSessionItemUiState(),
        messages = overlay.messages ?: history.messages.map { it.toSessionMessageUiState() }.chronological(),
        composerText = overlay.composerText,
        sendErrorCategory = overlay.sendErrorCategory,
        hasUnresolvedSubmission = overlay.hasUnresolvedSubmission,
        latestRun = overlay.latestRun ?: history.latestRun(),
        activeRuns = overlay.activeRuns ?: history.runs().activeRuns(),
        isSending = overlay.isSending,
        latestRunState = overlay.latestRunState,
        latestRunRetryAvailable = overlay.latestRunRetryAvailable,
        activeResponse = overlay.activeResponse,
        isRefreshing = overlay.isRefreshing,
    )

private fun RunObservationState.toSessionMessageUiState(retryInput: String? = null): SessionMessageUiState =
    SessionMessageUiState(
        id = "active-response:${run.id.value}",
        role = "assistant",
        content = responseText.takeIf(String::isNotEmpty),
        runId = run.id,
        runState = state,
        isStreaming = isStreaming,
        streamInterrupted = isStreamInterrupted,
        failureSafeMessage =
            if (state == RunPresentationState.FAILED) {
                RunFailureCategory.GATEWAY_REPORTED.safeMessage
            } else {
                null
            },
        retryAvailable = isRunRetryEligible(state, retryInput),
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
    val sessionGeneration: Long? = null,
    val updateVisibleUi: Boolean = false,
)

private data class RecoveryGatewayContext(
    val endpoint: String,
    val sessionGateway: SessionGatewayPort,
    val runGateway: RunGatewayPort,
)

private data class SessionMutationRequest(
    val sessionId: SessionId,
    val gateway: SessionGatewayPort,
    val connectionGeneration: Long,
    val attemptId: Long,
)

private data class RecoveryEntryKey(
    val endpoint: String,
    val sessionId: SessionId,
    val runId: RunId,
)

private data class RecoveryClaim(
    val key: RecoveryEntryKey,
    val connectionGeneration: Long,
    val claimId: Long,
)

private data class RecoveryWorkItem(
    val entry: RunRecoveryEntry,
    val claim: RecoveryClaim,
)

private data class RecoverySessionKey(
    val endpoint: String,
    val sessionId: SessionId,
)

private data class PendingSubmissionPersistence(
    val key: PendingRunSubmissionKey,
    val recoveryKey: RecoverySessionKey,
    val knownRunIds: Set<RunId>,
    val attemptId: String,
)

private data class PendingCreateState(
    val knownRunIds: Set<RunId>,
    val attemptId: String,
)

private data class PendingDraft(
    val text: String,
    val revision: Long,
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

private data class ReconciledObservationRelease(
    val job: Job? = null,
    val observation: RunEventObservation? = null,
    val wasActivelyObserved: Boolean = false,
)

private data class OpenedRunReconcileContext(
    val sessionId: SessionId,
    val requestSessionGeneration: Long,
    val requestConnectionGeneration: Long,
    val sessionGateway: SessionGatewayPort,
    val runIdToReconcile: RunId? = null,
    val restartObservation: Boolean = false,
    val clearRefreshWhenNoRun: Boolean = false,
)

private data class RunSubmissionWork(
    val gateway: RunGatewayPort,
    val input: String,
    val rejectRunId: RunId?,
    val sessionId: SessionId,
    val requestConnectionGeneration: Long,
    val requestEndpoint: String,
    val recoverySessionKey: RecoverySessionKey,
    val pendingCreate: PendingCreateState,
    val attemptId: String,
    val knownRunIds: Set<RunId>,
)

private data class PreparedRunSubmission(
    val job: Job,
    val persistence: PendingSubmissionPersistence,
)

private data class SubmittedRunApplication(
    val sessionId: SessionId,
    val run: Run,
    val requestConnectionGeneration: Long,
    val requestEndpoint: String,
    val attemptId: String,
    val pendingKey: PendingRunSubmissionKey,
    val submissionBindingSucceeded: Boolean,
    val recoveryPersistenceFailed: Boolean,
)

private data class SubmittedRunPreparation(
    val pendingKey: PendingRunSubmissionKey,
    val submissionBindingSucceeded: Boolean,
    val persistBeforeApply: Boolean,
    val recoveryPersistenceFailed: Boolean,
)

private data class AuthoritativeReconciliationRequest(
    val sessionId: SessionId,
    val connectionGeneration: Long,
    val sessionGeneration: Long?,
    val historyGeneration: Long? = null,
    val updateVisibleUi: Boolean = true,
)

private typealias RunRecoveryStart = Pair<RunRecoveryRegistry, RecoveryGatewayContext>

private data class ReconciledRunApplication(
    val observationJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
    val recoveryEntryToRemove: RunRecoveryEntry? = null,
    val recoveryEndpointToRemove: String? = null,
)

private data class SubmittedRunApplicationResult(
    val applied: Boolean,
    val persistAfterDisconnect: Boolean = false,
    val requestSessionGeneration: Long = 0L,
    val shouldObserve: Boolean = false,
    val observationJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
)

private data class RunObservationStart(
    val jobToStart: Job? = null,
    val observerJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
    val handoffRequested: Boolean = false,
)

private data class TimedOutSendAttempt(
    val sessionId: SessionId,
    val gateways: Pair<SessionGatewayPort, RunGatewayPort>,
    val knownRunIds: Set<RunId>,
    val requestEndpoint: String,
    val request: ReconciliationRequest,
    val resolveWhenNoNewRun: Boolean,
)

private data class TimedOutSendUncertainty(
    val pendingKey: PendingRunSubmissionKey,
    val pendingRecovery: TimedOutSendRecovery?,
    val recoveryDraft: PendingDraft?,
    val attemptId: String,
    val authoritativeMessages: List<SessionMessageUiState>,
    val retainedAuthoritativeRuns: List<Run>,
    val authoritativeRuns: List<Run>,
    val durableBoundRunId: RunId?,
    val discoveredUnattributedRun: Boolean,
    val boundSubmissionRun: Run?,
    val discoveredLocalRun: Run?,
    val submissionRun: Run?,
    val terminalRunIds: Set<RunId>,
    val canClearUncertainty: Boolean,
)

private data class TimedOutSendRecovery(
    val knownRunIds: Set<RunId>,
    val attemptId: String,
    val draft: PendingDraft,
    val errorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
)

private data class TimedOutSendUiContext(
    val current: SessionListUiState,
    val opened: OpenSessionUiState,
    val recoveryDraft: PendingDraft? = null,
    val authoritativeMessages: List<SessionMessageUiState>? = null,
)

private data class TimedOutSendReconciliationRequest(
    val sessionId: SessionId,
    val connectionGeneration: Long,
    val knownRunIds: Set<RunId>,
    val attemptId: String,
    val resolveWhenNoNewRun: Boolean = false,
    val uncertaintyErrorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
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
    val offset: Int?,
)

private data class CreateSessionRequest(
    val context: SessionRequestContext,
    val title: String?,
)

private typealias SessionMutationMap = Map<SessionId, SessionMutationUiState>

private fun List<SessionItemUiState>.orderedSessions(): List<SessionItemUiState> {
    return filter { it.pinned } + filterNot { it.pinned }
}

private fun unsentRenameDrafts(mutations: SessionMutationMap): SessionMutationMap {
    return mutations
        .mapNotNull { (sessionId, mutation) ->
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
}

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
            bearerCredential = configuredCredential.orEmpty(),
            saveCredential = configuredCredential != null,
            isGatewayConnectionConfigured = true,
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
