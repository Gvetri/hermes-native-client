package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationPermission
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationSettingsStore
import org.hermesnative.client.feature.entry.domain.RunStatusNotifier
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal fun notificationSuccessfulGateway(
    session: Session,
    runId: String,
): NotificationScriptedGateway {
    val run = Run(RunId(runId), session.id, "starting")
    return NotificationScriptedGateway(session).apply {
        enqueueRun(run)
        enqueueHistory(SessionHistory(session.id, emptyList()))
        enqueueHistory(
            SessionHistory(
                session.id,
                listOf(
                    org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                        id = "assistant-1",
                        role = "assistant",
                        content = "Hello world",
                        runId = run.id,
                        runStatus = "succeeded",
                    ),
                ),
            ),
        )
        enqueueStatus(run.copy(status = "succeeded"))
        observation =
            NotificationScriptedObservation(
                listOf(
                    RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                    RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                ),
            )
    }
}

internal class NotificationHolderSettings(
    val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val store: FakeNotificationSettingsStore = FakeNotificationSettingsStore(),
    val permission: FakeNotificationPermission =
        FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
    val notifier: FakeRunStatusNotifier = FakeRunStatusNotifier(),
    val permissionRequests: AtomicInteger = AtomicInteger(0),
    val recoveryRegistry: RunRecoveryRegistry? = null,
)

internal fun notificationHolder(
    gateway: NotificationScriptedGateway,
    settings: NotificationHolderSettings = NotificationHolderSettings(),
): EntryStateHolder {
    val repository: GatewayConnectionRepository =
        DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
    return EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(repository) { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
            },
        scope = CoroutineScope(SupervisorJob() + settings.dispatcher),
        sessionGatewayFactory = { _, _ -> gateway },
        runGatewayFactory = { _, _ -> gateway },
        dependencies =
            EntryStateHolderDependencies(
                runRecoveryRegistry = settings.recoveryRegistry,
                removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                runStatusNotificationSettingsStore = settings.store,
                runStatusNotificationPermission = settings.permission,
                runStatusNotifier = settings.notifier,
            ),
    ).also { holder ->
        holder.requestRunStatusNotificationPermission = { settings.permissionRequests.incrementAndGet() }
    }
}

internal fun connectAndOpenNotifiedSession(
    holder: EntryStateHolder,
    gateway: NotificationScriptedGateway,
    sessionId: SessionId,
) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("[REDACTED]"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    awaitNotificationState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
    holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
    awaitNotificationState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
}

internal fun awaitNotificationState(
    holder: EntryStateHolder,
    predicate: (EntryUiState) -> Boolean,
) {
    runBlocking {
        try {
            withTimeout(NOTIFICATION_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val state = holder.uiState.value
            error(
                "Timed out waiting for state. Current: " +
                    "opened=${state.sessionList?.openedSession != null}, " +
                    "latestRunState=${state.sessionList?.openedSession?.latestRunState}, " +
                    "sendError=${state.sessionList?.openedSession?.sendErrorCategory}, " +
                    "error=${state.sessionList?.openedSession?.errorCategory}, " +
                    "composer=${state.sessionList?.openedSession?.composerText}, " +
                    "runs=${state.sessionList?.openedSession?.activeRuns?.map { it.id }}, " +
                    "sessions=${state.sessionList?.sessions?.map { it.id }}",
            )
        }
    }
}

internal fun notificationSession(id: String): Session =
    Session(
        id = SessionId(id),
        title = "Session $id",
        preview = "Preview",
        pinned = false,
    )

internal class FakeNotificationSettingsStore(
    var enabled: Boolean = false,
) : RunStatusNotificationSettingsStore {
    override fun loadEnabled(): Boolean = enabled

    override fun saveEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}

internal class FakeNotificationPermission(
    var canPost: Boolean,
    var requiresRuntimePermissionRequest: Boolean,
) : RunStatusNotificationPermission {
    override fun canPost(): Boolean = canPost

    override fun requiresRuntimePermissionRequest(): Boolean = requiresRuntimePermissionRequest
}

internal class FakeRunStatusNotifier : RunStatusNotifier {
    val posted = CopyOnWriteArrayList<Pair<RunId, RunPresentationState>>()

    override fun postTerminal(
        run: Run,
        state: RunPresentationState,
    ) {
        posted += run.id to state
    }
}

internal class NotificationScriptedGateway(
    val session: Session,
) : SessionGatewayPort, RunGatewayPort {
    private val runResults = java.util.ArrayDeque<Run>()
    private val historyResults = java.util.ArrayDeque<SessionHistory>()
    private val statusResults = java.util.ArrayDeque<Run>()
    var observation: RunEventObservation = NotificationScriptedObservation(emptyList())

    fun enqueueRun(run: Run) {
        runResults += run
    }

    fun enqueueHistory(history: SessionHistory) {
        historyResults += history
    }

    fun enqueueStatus(run: Run) {
        statusResults += run
    }

    override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session = session

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
        if (historyResults.isEmpty()) {
            SessionHistory(sessionId, emptyList())
        } else {
            historyResults.removeFirst()
        }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId): Unit = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run = runResults.removeFirst()

    override fun getRunStatus(runId: RunId): Run =
        if (statusResults.isEmpty()) {
            error("not used")
        } else {
            statusResults.removeFirst()
        }

    override fun observeRun(runId: RunId): RunEventObservation = observation
}

internal open class NotificationScriptedObservation(
    private val events: List<RunEvent>,
) : RunEventObservation {
    override fun iterator(): Iterator<RunEvent> = events.iterator()

    override fun close() = Unit
}

internal class NotificationThrowingObservation(
    events: List<RunEvent>,
) : NotificationScriptedObservation(events) {
    override fun iterator(): Iterator<RunEvent> {
        val delegate = super.iterator()
        return object : Iterator<RunEvent> {
            override fun hasNext(): Boolean =
                if (delegate.hasNext()) {
                    true
                } else {
                    error("stream interrupted")
                }

            override fun next(): RunEvent = delegate.next()
        }
    }
}

internal const val NOTIFICATION_TIMEOUT_MILLIS = 5_000L
