package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun observationHolder(
    gateway: ObservationScriptedGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    recoveryRegistry: RunRecoveryRegistry? = null,
    persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
): EntryStateHolder {
    val repository: GatewayConnectionRepository =
        DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
    return EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(repository) { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
            },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        sessionGatewayFactory = { _, _ -> gateway },
        runGatewayFactory = { _, _ -> gateway },
        dependencies =
            EntryStateHolderDependencies(
                runRecoveryRegistry = recoveryRegistry,
                persistRunRecoveryEntry = persistRunRecoveryEntry,
                removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
            ),
    )
}

internal fun openObservedSession(
    holder: EntryStateHolder,
    gateway: ObservationScriptedGateway,
    sessionId: SessionId,
) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("[REDACTED]"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    awaitObservationState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
    holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
    awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
}

internal fun awaitObservationState(
    holder: EntryStateHolder,
    predicate: (EntryUiState) -> Boolean,
) {
    runBlocking {
        withTimeout(OBSERVATION_TIMEOUT_MILLIS) {
            holder.uiState.first(predicate)
        }
    }
}

internal fun awaitObservationCondition(predicate: () -> Boolean) {
    runBlocking {
        withTimeout(OBSERVATION_TIMEOUT_MILLIS) {
            while (!predicate()) delay(10)
        }
    }
}

internal fun observationSession(id: String): Session =
    Session(
        id = SessionId(id),
        title = "Session $id",
        preview = "Preview",
        pinned = false,
    )

internal class ObservationScriptedGateway(
    val session: Session,
    private val additionalSessions: List<Session> = emptyList(),
) : SessionGatewayPort, RunGatewayPort {
    private val runResults = ArrayDeque<Run>()
    private val historyResults = ArrayDeque<SessionHistory>()
    private val statusResults = ArrayDeque<Run>()
    val statusByRun = ConcurrentHashMap<RunId, Run>()
    val historyBySession = ConcurrentHashMap<SessionId, SessionHistory>()
    var observation: RunEventObservation = ObservationScriptedObservation(emptyList())
    val observedRunIds = CopyOnWriteArrayList<RunId>()
    val statusRequests = CopyOnWriteArrayList<RunId>()
    val cancelledRunIds = CopyOnWriteArrayList<RunId>()
    val createRunCount = AtomicInteger(0)
    var blockCreate = false
    val createStarted = CountDownLatch(1)
    val createRelease = CountDownLatch(1)
    var blockStatus = false
    val statusStarted = CountDownLatch(1)
    val statusRelease = CountDownLatch(1)
    var blockOpenFor: SessionId? = null
    val openStarted = CountDownLatch(1)
    val openRelease = CountDownLatch(1)
    var failOpenFor: SessionId? = null

    fun enqueueRun(run: Run) {
        runResults += run
    }

    fun enqueueHistory(history: SessionHistory) {
        historyResults += history
    }

    fun enqueueStatus(run: Run) {
        statusResults += run
    }

    override fun listSessions(request: SessionListRequest): SessionPage =
        SessionPage(
            listOf(session) + additionalSessions,
            null,
        )

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session {
        if (failOpenFor == sessionId) error("gateway unavailable")
        if (blockOpenFor == sessionId) {
            openStarted.countDown()
            openRelease.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        return (listOf(session) + additionalSessions).single { it.id == sessionId }
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
        historyBySession[sessionId]
            ?: if (historyResults.isEmpty()) {
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
    ): Run {
        createRunCount.incrementAndGet()
        if (blockCreate) {
            createStarted.countDown()
            createRelease.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        return runResults.removeFirst()
    }

    override fun getRunStatus(runId: RunId): Run {
        statusRequests += runId
        if (blockStatus) {
            statusStarted.countDown()
            statusRelease.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        return statusByRun[runId]
            ?: if (statusResults.isEmpty()) {
                error("not used")
            } else {
                statusResults.removeFirst()
            }
    }

    override fun observeRun(runId: RunId): RunEventObservation {
        observedRunIds += runId
        return observation
    }
}

internal open class ObservationScriptedObservation(
    private val events: List<RunEvent>,
) : RunEventObservation {
    override fun iterator(): Iterator<RunEvent> = events.iterator()

    override fun close() = Unit
}

internal class ObservationThrowingObservation(
    events: List<RunEvent>,
) : ObservationScriptedObservation(events) {
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

internal class ObservationBlockingObservation : RunEventObservation {
    val started = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val closeCount = AtomicInteger(0)

    override fun iterator(): Iterator<RunEvent> =
        object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                started.countDown()
                release.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                return false
            }

            override fun next(): RunEvent = error("not used")
        }

    override fun close() {
        if (closeCount.incrementAndGet() == 1) {
            closed.countDown()
        }
        release.countDown()
    }
}

internal class ObservationDelayedTerminalObservation(
    private val terminalEvent: RunEvent,
) : RunEventObservation {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    private var emitted = false

    override fun iterator(): Iterator<RunEvent> =
        object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                started.countDown()
                check(release.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Observation was not released."
                }
                return !emitted
            }

            override fun next(): RunEvent {
                check(!emitted) { "Terminal event was already emitted." }
                emitted = true
                return terminalEvent
            }
        }

    override fun close() {
        release.countDown()
    }
}

internal class ObservationFailingRunRecoveryRegistry : RunRecoveryRegistry {
    override fun load(): List<RunRecoveryEntry> = emptyList()

    override fun save(entry: RunRecoveryEntry): Unit = error("recovery storage unavailable")

    override fun remove(entry: RunRecoveryEntry) = Unit
}

internal class ObservationFailingLoadRunRecoveryRegistry : RunRecoveryRegistry {
    override fun load(): List<RunRecoveryEntry> = error("recovery storage unavailable")

    override fun save(entry: RunRecoveryEntry) = Unit

    override fun remove(entry: RunRecoveryEntry) = Unit
}

internal class ObservationFlakyRunRecoveryRegistry : RunRecoveryRegistry {
    private val entries = CopyOnWriteArrayList<RunRecoveryEntry>()
    var failSave = true
    val saveAttempts = AtomicInteger(0)

    override fun load(): List<RunRecoveryEntry> = entries.toList()

    override fun save(entry: RunRecoveryEntry) {
        saveAttempts.incrementAndGet()
        if (failSave) error("recovery storage unavailable")
        if (entry !in entries) entries += entry
    }

    override fun remove(entry: RunRecoveryEntry) {
        entries -= entry
    }
}

internal class ObservationFailingRemoveRunRecoveryRegistry(
    private val entry: RunRecoveryEntry,
) : RunRecoveryRegistry {
    override fun load(): List<RunRecoveryEntry> = listOf(entry)

    override fun save(entry: RunRecoveryEntry) = Unit

    override fun remove(entry: RunRecoveryEntry): Unit = error("recovery cleanup unavailable")
}

internal const val OBSERVATION_TIMEOUT_MILLIS = 5_000L
