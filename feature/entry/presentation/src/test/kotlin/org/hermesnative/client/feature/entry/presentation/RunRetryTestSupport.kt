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
import org.hermesnative.client.feature.entry.data.DefaultRunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.data.RunSubmissionUncertaintyStorage
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val RETRY_TIMEOUT_MILLIS = 5_000L

internal class RetryHolderSettings(
    val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val onRunSubmissionSettled: (() -> Unit)? = null,
    val recoveryRegistry: RunRecoveryRegistry? = null,
    val uncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
    val persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    val removeRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
)

internal fun retryHolder(
    gateway: RetryFakeGateway,
    settings: RetryHolderSettings = RetryHolderSettings(),
): EntryStateHolder =
    EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(
                DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
            ) { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
            },
        scope = CoroutineScope(SupervisorJob() + settings.dispatcher),
        sessionGatewayFactory = { _, _ -> gateway },
        runGatewayFactory = { _, _ -> gateway },
        dependencies =
            EntryStateHolderDependencies(
                runRecoveryRegistry = settings.recoveryRegistry,
                removeGatewayConnectionUseCase =
                    RemoveGatewayConnection(
                        DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
                    ),
                runSubmissionUncertaintyStore = settings.uncertaintyStore,
                persistRunRecoveryEntry = settings.persistRunRecoveryEntry,
                removeRunRecoveryEntry = settings.removeRunRecoveryEntry,
                onRunSubmissionSettled = settings.onRunSubmissionSettled,
            ),
    )

internal fun persistentRetryHolder(
    gateway: RetryFakeGateway,
    registry: InMemoryRunRecoveryRegistry,
): EntryStateHolder =
    retryHolder(
        gateway,
        RetryHolderSettings(
            dispatcher = Dispatchers.Default,
            recoveryRegistry = registry,
            uncertaintyStore = DefaultRunSubmissionUncertaintyStore(RetryUncertaintyStorage()),
            persistRunRecoveryEntry = { _, entry -> registry.save(entry) },
            removeRunRecoveryEntry = { _, entry -> registry.remove(entry) },
        ),
    )

internal fun connectRetryGateway(
    holder: EntryStateHolder,
    gateway: RetryFakeGateway,
) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    awaitRetryState(holder) {
        it.sessionList?.sessions ==
            gateway.sessions.map { session -> session.toSessionItemUiState() }
    }
}

internal fun openRetrySession(
    holder: EntryStateHolder,
    gateway: RetryFakeGateway,
    sessionId: SessionId,
) {
    connectRetryGateway(holder, gateway)
    holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
    awaitRetryState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
}

internal fun awaitRetryState(
    holder: EntryStateHolder,
    predicate: (EntryUiState) -> Boolean,
) {
    runBlocking {
        withTimeout(RETRY_TIMEOUT_MILLIS) {
            holder.uiState.first(predicate)
        }
    }
}

internal fun retrySession(id: String): Session =
    Session(
        id = SessionId(id),
        title = "Session $id",
        preview = "Preview",
        pinned = false,
    )

internal fun retryHistory(
    sessionId: SessionId,
    vararg messages: GatewayHistoryMessage,
): SessionHistory = SessionHistory(sessionId, messages.toList())

internal fun retryUserMessage(
    content: String,
    runId: RunId,
): GatewayHistoryMessage =
    GatewayHistoryMessage(
        id = "user:$runId.value",
        role = "user",
        content = content,
        runId = runId,
    )

internal fun retryFailedMessage(
    runId: RunId,
    result: String,
): GatewayHistoryMessage =
    GatewayHistoryMessage(
        id = "result:$runId.value",
        role = null,
        content = null,
        runId = runId,
        runStatus = "failed",
        runResult = result,
    )

internal class RetryUncertaintyStorage : RunSubmissionUncertaintyStorage {
    private val records = mutableMapOf<PendingRunSubmissionKey, RunSubmissionUncertaintySnapshot>()

    override fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? = records[key]

    override fun write(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ) {
        records[key] = snapshot
    }

    override fun remove(key: PendingRunSubmissionKey) {
        records.remove(key)
    }
}

internal class RetryFakeGateway(
    val sessions: List<Session>,
    private val histories: Map<SessionId, SessionHistory> = emptyMap(),
    private val runStatuses: Map<RunId, Run> = emptyMap(),
) : SessionGatewayPort, RunGatewayPort {
    private val mutableHistories = histories.toMutableMap()
    private val mutableRunStatuses = runStatuses.toMutableMap()
    private val runObservations = mutableMapOf<RunId, List<RunEvent>>()
    val runRequests = mutableListOf<Pair<SessionId, String>>()
    private val runResults = ArrayDeque<Result<Run>>()
    val runStarted = CountDownLatch(1)
    val runFinished = CountDownLatch(1)
    val releaseRun = CountDownLatch(1)
    var blockRunCreation = false

    fun enqueueRun(run: Run) {
        runResults += Result.success(run)
    }

    fun enqueueRunFailure(error: GatewayException) {
        runResults += Result.failure(error)
    }

    fun enqueueObservation(
        runId: RunId,
        vararg events: RunEvent,
    ) {
        runObservations[runId] = events.toList()
    }

    fun setRunStatus(run: Run) {
        mutableRunStatuses[run.id] = run
    }

    fun setHistory(history: SessionHistory) {
        mutableHistories[history.sessionId] = history
    }

    override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(sessions, null)

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session = sessions.single { it.id == sessionId }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
        mutableHistories[sessionId] ?: SessionHistory(sessionId, emptyList())

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
    ): Run {
        runRequests += sessionId to input
        if (blockRunCreation) {
            runStarted.countDown()
            check(releaseRun.await(RETRY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting for Run release."
            }
        }
        return runResults.removeFirst().getOrThrow()
    }

    override fun getRunStatus(runId: RunId): Run = mutableRunStatuses[runId] ?: error("not used")

    override fun observeRun(runId: RunId): RunEventObservation =
        object : RunEventObservation {
            override fun iterator(): Iterator<RunEvent> = runObservations[runId].orEmpty().iterator()

            override fun close() = Unit
        }
}
