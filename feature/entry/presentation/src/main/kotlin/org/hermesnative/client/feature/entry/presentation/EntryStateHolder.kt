package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsExporter
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsRecorder
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticsStore
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationPermission
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationSettingsStore
import org.hermesnative.client.feature.entry.domain.RunStatusNotifier
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.toRunPresentationState
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EntryUiEvent {
    sealed interface Connection : EntryUiEvent

    data object AddGatewayConnectionClicked : Connection

    data object ChangeGatewayCredentialClicked : Connection

    data object CancelGatewayCredentialChangeClicked : Connection

    data class EndpointChanged(
        val value: String,
    ) : Connection

    data class BearerCredentialChanged(
        val value: String,
    ) : Connection

    data class SaveCredentialChanged(
        val value: Boolean,
    ) : Connection

    data object VerifyGatewayConnectionClicked : Connection

    data object TryAgainClicked : Connection

    data object RemoveGatewayConnectionClicked : Connection

    data object RunStatusNotificationsToggleClicked : Connection

    data class RunStatusNotificationPermissionResult(
        val granted: Boolean,
    ) : Connection

    sealed interface SessionNavigation : EntryUiEvent

    data object RefreshSessionsClicked : SessionNavigation

    data object RefreshSessionListClicked : SessionNavigation

    data class SessionSearchQueryChanged(
        val value: String,
    ) : SessionNavigation

    data object ClearSessionSearchClicked : SessionNavigation

    data object LoadMoreSessionsClicked : SessionNavigation

    data class SessionClicked(
        val sessionId: SessionId,
    ) : SessionNavigation

    data object ReturnToSessionListClicked : SessionNavigation

    sealed interface SessionMutation : EntryUiEvent

    data object CreateSessionClicked : SessionMutation

    data class CreateSessionTitleChanged(
        val value: String,
    ) : SessionMutation

    data object ConfirmCreateSessionClicked : SessionMutation

    data object CancelCreateSessionClicked : SessionMutation

    data class RenameSessionClicked(
        val sessionId: SessionId,
    ) : SessionMutation

    data class RenameSessionTitleChanged(
        val sessionId: SessionId,
        val value: String,
    ) : SessionMutation

    data class ConfirmRenameSessionClicked(
        val sessionId: SessionId,
    ) : SessionMutation

    data class CancelRenameSessionClicked(
        val sessionId: SessionId,
    ) : SessionMutation

    sealed interface SessionPinAndDelete : EntryUiEvent

    data class PinSessionClicked(
        val sessionId: SessionId,
    ) : SessionPinAndDelete

    data class UnpinSessionClicked(
        val sessionId: SessionId,
    ) : SessionPinAndDelete

    data class DeleteSessionClicked(
        val sessionId: SessionId,
    ) : SessionPinAndDelete

    data class ConfirmDeleteSessionClicked(
        val sessionId: SessionId,
    ) : SessionPinAndDelete

    data class CancelDeleteSessionClicked(
        val sessionId: SessionId,
    ) : SessionPinAndDelete

    sealed interface Run : EntryUiEvent

    data class ComposerTextChanged(
        val value: String,
    ) : Run

    data object SendMessageClicked : Run

    data class RetryRunClicked(
        val runId: RunId,
    ) : Run

    sealed interface Diagnostics : EntryUiEvent

    data object OpenLocalDiagnosticsClicked : Diagnostics

    data object CloseLocalDiagnosticsClicked : Diagnostics

    data object ExportDiagnosticsClicked : Diagnostics

    data object ClearDiagnosticsClicked : Diagnostics

    data object ConfirmClearDiagnosticsClicked : Diagnostics

    data object CancelClearDiagnosticsClicked : Diagnostics
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

internal const val DEFAULT_SEND_TIMEOUT_MILLIS = 30_000L
internal const val RECOVERY_CLAIM_ATTEMPTS = 100
internal const val RECOVERY_CLAIM_RETRY_MILLIS = 10L
internal const val UNCERTAIN_RUN_STATUS = "uncertain"
internal const val RECOVERY_PENDING_STATUS = "recovery_pending"

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

internal data class ReleasedConnectionState(
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
    internal val verifyGatewayConnection: VerifyGatewayConnection? = null,
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    internal val sessionGatewayFactory: ((endpoint: String, bearerCredential: String) -> SessionGatewayPort)? = null,
    internal val runGatewayFactory: ((endpoint: String, bearerCredential: String) -> RunGatewayPort)? = null,
    dependencies: EntryStateHolderDependencies = EntryStateHolderDependencies(),
) {
    internal val runRecoveryRegistry = dependencies.runRecoveryRegistry
    internal val updateRunRecoveryEndpoint = dependencies.updateRunRecoveryEndpoint
    internal val persistRunRecoveryEntry = dependencies.persistRunRecoveryEntry
    internal val removeRunRecoveryEntry = dependencies.removeRunRecoveryEntry
    internal val removeGatewayConnectionUseCase = dependencies.removeGatewayConnectionUseCase
    internal val runSubmissionUncertaintyStore = dependencies.runSubmissionUncertaintyStore
    internal val onRunSubmissionCompleted = dependencies.onRunSubmissionCompleted
    internal val onRunSubmissionSettled = dependencies.onRunSubmissionSettled
    internal val runStatusNotificationSettingsStore = dependencies.runStatusNotificationSettingsStore
    internal val runStatusNotificationPermission = dependencies.runStatusNotificationPermission
    internal val runStatusNotifier = dependencies.runStatusNotifier
    internal val localDiagnostics = dependencies.localDiagnostics
    internal val sendTimeoutMillis = dependencies.sendTimeoutMillis

    /**
     * Requests the Android notification permission. Assigned by the app
     * composition root before the Settings control is reachable.
     */
    var requestRunStatusNotificationPermission: (() -> Unit)? = null

    init {
        require(sendTimeoutMillis > 0) { "sendTimeoutMillis must be positive." }
    }

    internal val mutableUiState =
        MutableStateFlow(
            initialState.toUiState().copy(runStatusNotifications = restoredRunStatusNotificationState()),
        )
    val uiState: StateFlow<EntryUiState> = mutableUiState.asStateFlow()
    internal var verificationJob: Job? = null
    internal var sessionJob: Job? = null
    internal var recoveryJob: Job? = null
    internal var sessionGateway: SessionGatewayPort? = null
    internal var runGateway: RunGatewayPort? = null
    internal val sessionRequestLock = Any()
    internal val recoveryPersistenceLock = Any()
    internal val connectionPersistenceLock = Any()
    internal val localDiagnosticsClearInFlight = AtomicBoolean(false)
    internal var sessionRequestGeneration = 0L
    internal var connectionGeneration = 0L
    internal val mutationJobs = mutableMapOf<SessionId, Job>()
    internal var nextMutationAttemptId = 0L
    internal val mutationOwners = mutableMapOf<SessionId, SessionMutationRequest>()
    internal val runJobs = mutableMapOf<SessionId, Job>()
    internal val sessionDrafts = mutableMapOf<SessionId, String>()
    internal val sessionDraftRevisions = mutableMapOf<SessionId, Long>()
    internal val sessionSendErrors = mutableMapOf<SessionId, MessageSendErrorCategory>()
    internal val pendingRunDrafts = mutableMapOf<SessionId, PendingDraft>()
    internal val pendingDisconnectedRecoveryEntries = mutableMapOf<String, MutableSet<RunRecoveryEntry>>()
    internal val connectionRecoveryJobs = mutableSetOf<Job>()
    internal val connectionRecoverySessionCounts = mutableMapOf<SessionId, Int>()
    internal val connectionRecoveryFailedSessions = mutableSetOf<SessionId>()
    internal val recoverySessionCounts = mutableMapOf<SessionId, Int>()
    internal var nextRecoveryClaimId = 0L
    internal val recoveryClaims = mutableMapOf<RecoveryEntryKey, RecoveryClaim>()
    internal val recoveryHandledEntries = mutableSetOf<RecoveryEntryKey>()
    internal var recoveryLoadPending = false
    internal var recoveryLoadFailed = false
    internal val pendingCreateSessions = mutableMapOf<RecoverySessionKey, PendingCreateState>()
    internal val pendingCreateStarted = mutableSetOf<RecoverySessionKey>()

    internal val sessionRuns = mutableMapOf<SessionId, List<Run>>()
    internal val submittedRunInputs = mutableMapOf<RunId, String>()
    internal val unresolvedLocalRunIds = mutableMapOf<RecoverySessionKey, MutableSet<RunId>>()
    internal val unresolvedLocalRunAttempts = mutableMapOf<RecoverySessionKey, MutableMap<RunId, String>>()
    internal val authoritativeSessionHistoryGenerations = mutableMapOf<SessionId, Long>()
    internal val authoritativeSessionRuns = mutableMapOf<SessionId, List<Run>>()
    internal val runObservationJobs = mutableMapOf<SessionId, Job>()
    internal val runObservations = mutableMapOf<SessionId, RunEventObservation>()
    internal val runObservationRunIds = mutableMapOf<SessionId, RunId>()
    internal val pendingRunObservationRequests = mutableMapOf<SessionId, ObserverStartRequest>()
    internal val runObservationStates = mutableMapOf<SessionId, MutableMap<RunId, RunObservationState>>()
    internal val unresolvedSubmissionSessions = mutableSetOf<SessionId>()
    internal val ambiguousSubmissionSessions = mutableSetOf<SessionId>()
    internal val recoveryUnavailableSessions = mutableSetOf<SessionId>()
    internal val unresolvedSessionMutations = mutableMapOf<RecoverySessionKey, SessionMutationUiState>()
    internal val uncertainSendDrafts = mutableMapOf<SessionId, PendingDraft>()
    internal val uncertainSubmissionRunIds = mutableMapOf<SessionId, RunId>()
    internal val uncertainSubmissionAttemptIds = mutableMapOf<SessionId, String>()
    internal val pendingTimedOutSends = mutableMapOf<SessionId, TimedOutSendRecovery>()
    internal val reconcilingSessions = mutableMapOf<SessionId, Long>()
    internal val notifiedTerminalRunIds = mutableSetOf<RunId>()

    fun onEvent(event: EntryUiEvent) {
        when (event) {
            is EntryUiEvent.Connection -> dispatchConnectionEvent(event)
            is EntryUiEvent.SessionNavigation -> dispatchSessionNavigationEvent(event)
            is EntryUiEvent.SessionMutation -> dispatchSessionMutationEvent(event)
            is EntryUiEvent.SessionPinAndDelete -> dispatchSessionPinDeleteEvent(event)
            is EntryUiEvent.Run -> dispatchRunEvent(event)
            is EntryUiEvent.Diagnostics -> dispatchDiagnosticsEvent(event)
        }
    }

    private fun dispatchConnectionEvent(event: EntryUiEvent.Connection) {
        when (event) {
            is EntryUiEvent.AddGatewayConnectionClicked -> showConnectionSetup()
            is EntryUiEvent.ChangeGatewayCredentialClicked -> showCredentialRotation()
            is EntryUiEvent.CancelGatewayCredentialChangeClicked -> cancelCredentialRotation()
            is EntryUiEvent.EndpointChanged -> updateEndpoint(event.value)
            is EntryUiEvent.BearerCredentialChanged -> updateBearerCredential(event.value)
            is EntryUiEvent.SaveCredentialChanged -> updateSaveCredential(event.value)
            is EntryUiEvent.VerifyGatewayConnectionClicked -> verifyConnection()
            is EntryUiEvent.TryAgainClicked -> verifyConnection()
            is EntryUiEvent.RemoveGatewayConnectionClicked -> removeGatewayConnection()
            is EntryUiEvent.RunStatusNotificationsToggleClicked -> toggleRunStatusNotifications()
            is EntryUiEvent.RunStatusNotificationPermissionResult ->
                applyRunStatusNotificationPermissionResult(event.granted)
        }
    }

    private fun dispatchSessionNavigationEvent(event: EntryUiEvent.SessionNavigation) {
        when (event) {
            is EntryUiEvent.RefreshSessionsClicked -> refreshSessions()
            is EntryUiEvent.RefreshSessionListClicked -> refreshSessionList()
            is EntryUiEvent.SessionSearchQueryChanged -> updateSearchQuery(event.value)
            is EntryUiEvent.ClearSessionSearchClicked -> clearSearch()
            is EntryUiEvent.LoadMoreSessionsClicked -> loadMoreSessions()
            is EntryUiEvent.SessionClicked -> openSession(event.sessionId)
            is EntryUiEvent.ReturnToSessionListClicked -> returnToSessionList()
        }
    }

    private fun dispatchSessionMutationEvent(event: EntryUiEvent.SessionMutation) {
        when (event) {
            is EntryUiEvent.CreateSessionClicked -> showCreateSession()
            is EntryUiEvent.CreateSessionTitleChanged -> updateCreateSessionTitle(event.value)
            is EntryUiEvent.ConfirmCreateSessionClicked -> confirmCreateSession()
            is EntryUiEvent.CancelCreateSessionClicked -> cancelCreateSession()
            is EntryUiEvent.RenameSessionClicked -> showRenameSession(event.sessionId)
            is EntryUiEvent.RenameSessionTitleChanged -> updateRenameSessionTitle(event.sessionId, event.value)
            is EntryUiEvent.ConfirmRenameSessionClicked -> confirmRenameSession(event.sessionId)
            is EntryUiEvent.CancelRenameSessionClicked -> cancelRenameSession(event.sessionId)
        }
    }

    private fun dispatchSessionPinDeleteEvent(event: EntryUiEvent.SessionPinAndDelete) {
        when (event) {
            is EntryUiEvent.PinSessionClicked -> pinSession(event.sessionId)
            is EntryUiEvent.UnpinSessionClicked -> unpinSession(event.sessionId)
            is EntryUiEvent.DeleteSessionClicked -> showDeleteSession(event.sessionId)
            is EntryUiEvent.ConfirmDeleteSessionClicked -> confirmDeleteSession(event.sessionId)
            is EntryUiEvent.CancelDeleteSessionClicked -> cancelDeleteSession(event.sessionId)
        }
    }

    private fun dispatchRunEvent(event: EntryUiEvent.Run) {
        when (event) {
            is EntryUiEvent.ComposerTextChanged -> updateComposerText(event.value)
            is EntryUiEvent.SendMessageClicked -> retryUncertainSubmissionOrSend()
            is EntryUiEvent.RetryRunClicked -> retryRun(event.runId)
        }
    }

    private fun dispatchDiagnosticsEvent(event: EntryUiEvent.Diagnostics) {
        when (event) {
            is EntryUiEvent.OpenLocalDiagnosticsClicked -> openLocalDiagnostics()
            is EntryUiEvent.CloseLocalDiagnosticsClicked -> closeLocalDiagnostics()
            is EntryUiEvent.ExportDiagnosticsClicked -> exportLocalDiagnostics()
            is EntryUiEvent.ClearDiagnosticsClicked -> requestClearLocalDiagnostics()
            is EntryUiEvent.ConfirmClearDiagnosticsClicked -> confirmClearLocalDiagnostics()
            is EntryUiEvent.CancelClearDiagnosticsClicked -> cancelClearLocalDiagnostics()
        }
    }

    internal fun OpenSessionUiState.withRunSnapshot(
        knownRuns: List<Run>,
        latestObservation: RunObservationState?,
    ): OpenSessionUiState {
        val latestRunState = latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState()
        return copy(
            latestRun = knownRuns.latestRun(),
            activeRuns = knownRuns.activeRuns(),
            latestRunState = latestRunState,
            latestRunRetryAvailable = latestRunRetryAvailable(knownRuns, latestRunState),
            activeResponse = observedMessageUiState(latestObservation),
        )
    }

    fun close() {
        val released = releaseConnectionState()
        released.jobs.forEach(Job::cancel)
        released.observations.forEach(RunEventObservation::close)
        scope.cancel()
    }
}

internal data class OpenSessionOverlay(
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

internal data class RecoveryRunContext(
    val sessionGateway: SessionGatewayPort,
    val runGateway: RunGatewayPort,
    val connectionGeneration: Long,
    val historyGeneration: Long,
    val sessionGeneration: Long? = null,
    val updateVisibleUi: Boolean = false,
)

internal data class RecoveryGatewayContext(
    val endpoint: String,
    val sessionGateway: SessionGatewayPort,
    val runGateway: RunGatewayPort,
)

internal data class SessionMutationRequest(
    val sessionId: SessionId,
    val gateway: SessionGatewayPort,
    val connectionGeneration: Long,
    val attemptId: Long,
)

internal data class RecoveryEntryKey(
    val endpoint: String,
    val sessionId: SessionId,
    val runId: RunId,
)

internal data class RecoveryClaim(
    val key: RecoveryEntryKey,
    val connectionGeneration: Long,
    val claimId: Long,
)

internal data class RecoveryWorkItem(
    val entry: RunRecoveryEntry,
    val claim: RecoveryClaim,
)

internal data class RecoverySessionKey(
    val endpoint: String,
    val sessionId: SessionId,
)

internal data class PendingSubmissionPersistence(
    val key: PendingRunSubmissionKey,
    val recoveryKey: RecoverySessionKey,
    val knownRunIds: Set<RunId>,
    val attemptId: String,
)

internal data class PendingCreateState(
    val knownRunIds: Set<RunId>,
    val attemptId: String,
)

internal data class PendingDraft(
    val text: String,
    val revision: Long,
)

internal data class ObserverStartRequest(
    val run: Run,
    val connectionGeneration: Long,
    val sessionGeneration: Long,
)

internal data class ReconciliationRequest(
    val connectionGeneration: Long,
    val sessionGeneration: Long,
)

internal data class ReconciledObservationRelease(
    val job: Job? = null,
    val observation: RunEventObservation? = null,
    val wasActivelyObserved: Boolean = false,
)

internal data class OpenedRunReconcileContext(
    val sessionId: SessionId,
    val requestSessionGeneration: Long,
    val requestConnectionGeneration: Long,
    val sessionGateway: SessionGatewayPort,
    val runIdToReconcile: RunId? = null,
    val restartObservation: Boolean = false,
    val clearRefreshWhenNoRun: Boolean = false,
)

internal data class RunSubmissionWork(
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

internal data class PreparedRunSubmission(
    val job: Job,
    val persistence: PendingSubmissionPersistence,
)

internal data class SubmittedRunApplication(
    val sessionId: SessionId,
    val run: Run,
    val requestConnectionGeneration: Long,
    val requestEndpoint: String,
    val attemptId: String,
    val pendingKey: PendingRunSubmissionKey,
    val submissionBindingSucceeded: Boolean,
    val recoveryPersistenceFailed: Boolean,
)

internal data class SubmittedRunPreparation(
    val pendingKey: PendingRunSubmissionKey,
    val submissionBindingSucceeded: Boolean,
    val persistBeforeApply: Boolean,
    val recoveryPersistenceFailed: Boolean,
)

internal data class AuthoritativeReconciliationRequest(
    val sessionId: SessionId,
    val connectionGeneration: Long,
    val sessionGeneration: Long?,
    val historyGeneration: Long? = null,
    val updateVisibleUi: Boolean = true,
)

internal typealias RunRecoveryStart = Pair<RunRecoveryRegistry, RecoveryGatewayContext>

internal data class ReconciledRunApplication(
    val observationJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
    val recoveryEntryToRemove: RunRecoveryEntry? = null,
    val recoveryEndpointToRemove: String? = null,
)

internal data class SubmittedRunApplicationResult(
    val applied: Boolean,
    val persistAfterDisconnect: Boolean = false,
    val requestSessionGeneration: Long = 0L,
    val shouldObserve: Boolean = false,
    val observationJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
)

internal data class RunObservationStart(
    val jobToStart: Job? = null,
    val observerJobToCancel: Job? = null,
    val observationToClose: RunEventObservation? = null,
    val handoffRequested: Boolean = false,
)

internal data class TimedOutSendAttempt(
    val sessionId: SessionId,
    val gateways: Pair<SessionGatewayPort, RunGatewayPort>,
    val knownRunIds: Set<RunId>,
    val requestEndpoint: String,
    val request: ReconciliationRequest,
    val resolveWhenNoNewRun: Boolean,
)

internal data class TimedOutSendUncertainty(
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

internal data class TimedOutSendRecovery(
    val knownRunIds: Set<RunId>,
    val attemptId: String,
    val draft: PendingDraft,
    val errorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
)

internal data class TimedOutSendUiContext(
    val current: SessionListUiState,
    val opened: OpenSessionUiState,
    val recoveryDraft: PendingDraft? = null,
    val authoritativeMessages: List<SessionMessageUiState>? = null,
)

internal data class TimedOutSendReconciliationRequest(
    val sessionId: SessionId,
    val connectionGeneration: Long,
    val knownRunIds: Set<RunId>,
    val attemptId: String,
    val resolveWhenNoNewRun: Boolean = false,
    val uncertaintyErrorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
)

internal data class TimedOutSendReconciliationOutcome(
    val applied: Boolean,
    val runToObserve: Run? = null,
    val runToPersist: RunRecoveryEntry? = null,
    val runToRemove: RunRecoveryEntry? = null,
)

internal data class SessionRequestContext(
    val generation: Long,
    val query: String,
    val offset: Int?,
)

internal data class CreateSessionRequest(
    val context: SessionRequestContext,
    val title: String?,
)

internal typealias SessionMutationMap = Map<SessionId, SessionMutationUiState>

internal fun EntryState.toUiState(): EntryUiState =
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
