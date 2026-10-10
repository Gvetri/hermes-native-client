package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.RunSubmissionUncertaintyStorage
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventType
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun assertParentCancellationReleasesObserver(cancelDuringRegistration: Boolean) {
    val session = reconciliationSession()
    val run = Run(RunId("parent-cancelled-run"), session.id, "running")
    val dispatcher = ReconciliationPausingDispatcher()
    val parent = SupervisorJob()
    val failures = CopyOnWriteArrayList<Throwable>()
    val scope = CoroutineScope(parent + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
    val gateway = ReconciliationFakeGateway(session).apply { runs.add(run) }
    val holder = reconciliationHolder(gateway, ReconciliationHolderSettings(scope = scope))
    val registrations = AtomicInteger(0)
    val observerJobs =
        object : LinkedHashMap<SessionId, Job>() {
            override fun put(
                key: SessionId,
                value: Job,
            ): Job? {
                check(registrations.incrementAndGet() <= 3) {
                    "Observer restart did not stop after parent cancellation."
                }
                val previous = super.put(key, value)
                if (cancelDuringRegistration) parent.cancel()
                return previous
            }
        }
    privateField(holder, "runObservationJobs", observerJobs)

    try {
        openReconciledSession(holder, gateway)
        dispatcher.paused = true
        holder.onEvent(EntryUiEvent.ComposerTextChanged("Cancel the parent"))
        holder.onEvent(EntryUiEvent.SendMessageClicked)
        dispatcher.runNext()
        if (!cancelDuringRegistration) {
            assertEquals(1, dispatcher.queuedCount)
            assertTrue(observerJobs.values.single().isActive)
        }

        scope.cancel()
        dispatcher.drain()
        runBlocking { withTimeout(RECONCILIATION_TIMEOUT_MILLIS) { parent.join() } }

        assertEquals("A cancelled parent must not register replacement observers.", 1, registrations.get())
        assertTrue("Unexpected coroutine failures: $failures", failures.isEmpty())
        assertTrue(parent.isCompleted)
        assertTrue(observerJobs.isEmpty())
        assertTrue((privateField(holder, "runObservationRunIds") as Map<*, *>).isEmpty())
        assertTrue((privateField(holder, "pendingRunObservationRequests") as Map<*, *>).isEmpty())
        assertTrue(gateway.observedRunIds.isEmpty())
        assertEquals(listOf(session.id to "Cancel the parent"), gateway.runRequests)
        assertEquals(run.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
    } finally {
        holder.close()
        dispatcher.drain()
        joinHolder(holder)
    }
}

internal fun connectReconciliationGateway(
    holder: EntryStateHolder,
    gateway: ReconciliationFakeGateway,
) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    awaitReconciliationState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
}

internal fun reconciliationSettles(predicate: () -> Boolean): Boolean {
    val appeared =
        runBlocking {
            try {
                withTimeout(RECONCILIATION_SETTLE_MILLIS) {
                    while (!predicate()) delay(5)
                }
                true
            } catch (_: TimeoutCancellationException) {
                false
            }
        }
    return appeared || predicate()
}

@Suppress("UNCHECKED_CAST")
internal fun recoveryUnavailableSessions(holder: EntryStateHolder): Set<SessionId> =
    privateField(holder, "recoveryUnavailableSessions") as Set<SessionId>

internal class ReconciliationArmingRegistry(
    private val failAfterRelease: Boolean = false,
) : RunRecoveryRegistry {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile
    var armed = false

    override fun load(): List<RunRecoveryEntry> {
        if (!armed) return emptyList()
        entered.countDown()
        check(release.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "recovery load was not released." }
        if (failAfterRelease) error("recovery registry is unavailable")
        return emptyList()
    }

    override fun save(entry: RunRecoveryEntry) = Unit

    override fun remove(entry: RunRecoveryEntry) = Unit
}

internal class ReconciliationHolderSettings(
    val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    val sendTimeoutMillis: Long = 30_000L,
    val recoveryRegistry: RunRecoveryRegistry? = null,
    val uncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
    val scope: CoroutineScope? = null,
)

internal fun reconciliationHolder(
    gateway: ReconciliationFakeGateway,
    settings: ReconciliationHolderSettings = ReconciliationHolderSettings(),
): EntryStateHolder {
    val scope = settings.scope ?: CoroutineScope(SupervisorJob() + settings.dispatcher)
    val repository: GatewayConnectionRepository =
        DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
    return EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(repository) { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
            },
        scope = scope,
        sessionGatewayFactory = { _, _ -> gateway },
        runGatewayFactory = { _, _ -> gateway },
        dependencies =
            EntryStateHolderDependencies(
                runRecoveryRegistry = settings.recoveryRegistry,
                runSubmissionUncertaintyStore = settings.uncertaintyStore,
                persistRunRecoveryEntry =
                    settings.recoveryRegistry?.let { registry -> { _, entry -> registry.save(entry) } },
                removeRunRecoveryEntry =
                    settings.recoveryRegistry?.let { registry -> { _, entry -> registry.remove(entry) } },
                removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                onRunSubmissionCompleted = {
                    gateway.submissionCompleted.countDown()
                    if (gateway.blockSubmissionCompletion) {
                        check(
                            gateway.releaseSubmissionCompletion
                                .await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                        ) {
                            "Submission completion was not released."
                        }
                    }
                },
                sendTimeoutMillis = settings.sendTimeoutMillis,
            ),
    )
}

internal fun openReconciledSession(
    holder: EntryStateHolder,
    gateway: ReconciliationFakeGateway,
) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    awaitReconciliationState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
    holder.onEvent(EntryUiEvent.SessionClicked(gateway.session.id))
    awaitReconciliationState(holder) {
        it.sessionList?.openedSession?.session?.id == gateway.session.id &&
            !it.sessionList!!.openedSession!!.isReconciliationInProgress
    }
}

internal fun awaitReconciliationState(
    holder: EntryStateHolder,
    predicate: (EntryUiState) -> Boolean,
) {
    runBlocking {
        try {
            withTimeout(RECONCILIATION_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        } catch (error: Throwable) {
            throw AssertionError("state=${holder.uiState.value}", error)
        }
    }
}

internal fun awaitReconciliationCondition(predicate: () -> Boolean) {
    runBlocking {
        withTimeout(RECONCILIATION_TIMEOUT_MILLIS) {
            while (!predicate()) delay(10)
        }
    }
}

internal fun recoveredDeltaObservation(run: Run): ReconciliationScriptedObservation =
    ReconciliationScriptedObservation(
        listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Recovered observation", "delta")),
    )

internal fun joinHolder(holder: EntryStateHolder) {
    runBlocking {
        withTimeout(RECONCILIATION_TIMEOUT_MILLIS) {
            (privateField(holder, "scope") as CoroutineScope).coroutineContext[Job]?.join()
        }
    }
}

internal fun reconciliationSession(): Session =
    Session(
        id = SessionId("session-1"),
        title = "Session",
        preview = "Preview",
        pinned = false,
    )

internal fun invokePersistedRecoveryEntry(
    holder: EntryStateHolder,
    endpoint: String,
    entry: RunRecoveryEntry,
) {
    holder.reconcilePersistedRecoveryEntryIfConnected(endpoint, entry)
}

internal class ReconciliationFakeGateway(
    val session: Session,
) : SessionGatewayPort, RunGatewayPort {
    val histories = ArrayDeque<SessionHistory>()
    val statuses = ArrayDeque<Run>()
    val runs = ArrayDeque<Run>()
    val runRequests: MutableList<Pair<SessionId, String>> = CopyOnWriteArrayList()
    val statusRequests: MutableList<RunId> = CopyOnWriteArrayList()
    val failStatusRunIds = mutableSetOf<RunId>()
    val observedRunIds: MutableList<RunId> = CopyOnWriteArrayList()
    val observations = ArrayDeque<RunEventObservation>()
    private val historyRequestCount = AtomicInteger(0)
    val historyRequests: Int get() = historyRequestCount.get()
    private val listRequestCount = AtomicInteger(0)
    val listRequests: Int get() = listRequestCount.get()
    var observation: RunEventObservation = ReconciliationScriptedObservation(emptyList())
    var blockRunCreation = false
    var failRunCreation = false
    var failHistoryRequest: Int? = null
    var blockNextStatus = false
    var blockSubmissionCompletion = false
    var blockNextObservationOpen = false
    val runStarted = CountDownLatch(1)
    val submissionCompleted = CountDownLatch(1)
    val runFinished = CountDownLatch(1)
    val releaseRun = CountDownLatch(1)
    val releaseSubmissionCompletion = CountDownLatch(1)
    val statusStarted = CountDownLatch(1)
    val statusFinished = CountDownLatch(1)
    val releaseStatus = CountDownLatch(1)
    val staleHistoryLoaded = CountDownLatch(1)
    val observationOpenStarted = CountDownLatch(1)
    val observationOpenFinished = CountDownLatch(1)
    val releaseObservationOpen = CountDownLatch(1)
    val secondObservationRequested = CountDownLatch(1)
    private val observationRequests = mutableListOf<RunId>()

    override fun listSessions(request: SessionListRequest): SessionPage {
        listRequestCount.incrementAndGet()
        return SessionPage(listOf(session), null)
    }

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session = session

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        val (history, shouldFail) =
            synchronized(this) {
                historyRequestCount.incrementAndGet()
                val history =
                    if (histories.isEmpty()) {
                        SessionHistory(sessionId, emptyList())
                    } else {
                        histories.removeFirst()
                    }
                history to (historyRequests == failHistoryRequest)
            }
        if (shouldFail) error("history request failed")
        if (history.messages.any { it.content == "stale-refresh" }) {
            staleHistoryLoaded.countDown()
        }
        return history
    }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId) = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run {
        runRequests += sessionId to input
        if (failRunCreation) error("run creation failed")
        if (blockRunCreation) {
            runStarted.countDown()
            try {
                check(releaseRun.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Run was not released."
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } finally {
                runFinished.countDown()
            }
        }
        return if (runs.isEmpty()) error("missing run") else runs.removeFirst()
    }

    override fun getRunStatus(runId: RunId): Run {
        val (shouldBlock, status) =
            synchronized(this) {
                statusRequests += runId
                if (failStatusRunIds.remove(runId)) error("status request failed")
                val shouldBlock = blockNextStatus.also { blockNextStatus = false }
                val status = if (statuses.isEmpty()) error("missing status") else statuses.removeFirst()
                shouldBlock to status
            }
        if (shouldBlock) {
            statusStarted.countDown()
            try {
                check(releaseStatus.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Status was not released."
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } finally {
                statusFinished.countDown()
            }
        }
        return status
    }

    override fun observeRun(runId: RunId): RunEventObservation {
        val shouldBlockOpen =
            synchronized(this) {
                observationRequests += runId
                if (observationRequests.size == 2) {
                    secondObservationRequested.countDown()
                }
                blockNextObservationOpen.also { blockNextObservationOpen = false }
            }
        if (shouldBlockOpen) {
            observationOpenStarted.countDown()
            awaitObservationOpenRelease()
            observationOpenFinished.countDown()
        }
        return synchronized(this) {
            observedRunIds += runId
            if (observations.isEmpty()) observation else observations.removeFirst()
        }
    }

    private fun awaitObservationOpenRelease() {
        while (true) {
            if (awaitReleaseOrInterrupted(releaseObservationOpen)) return
        }
    }

    private fun awaitReleaseOrInterrupted(latch: CountDownLatch): Boolean {
        return try {
            latch.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }
}

internal open class ReconciliationScriptedObservation(
    private val events: List<RunEvent>,
) : RunEventObservation {
    override fun iterator(): Iterator<RunEvent> = events.iterator()

    override fun close() = Unit
}

internal class ReconciliationThrowingObservation(
    events: List<RunEvent>,
) : ReconciliationScriptedObservation(events) {
    override fun iterator(): Iterator<RunEvent> {
        val delegate = super.iterator()
        return object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                return if (delegate.hasNext()) {
                    true
                } else {
                    error("stream interrupted")
                }
            }

            override fun next(): RunEvent = delegate.next()
        }
    }
}

internal class ReconciliationDelayedTerminalObservation(
    private val terminalEvent: RunEvent,
) : RunEventObservation {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    private var emitted = false

    override fun iterator(): Iterator<RunEvent> =
        object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                started.countDown()
                try {
                    check(release.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "Observation was not released."
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
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

internal class ReconciliationBlockingObservation(
    private val events: List<RunEvent> = emptyList(),
) : RunEventObservation {
    val started = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val closeCount = AtomicInteger(0)

    override fun iterator(): Iterator<RunEvent> {
        started.countDown()
        var nextEventIndex = 0
        return object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                if (nextEventIndex < events.size) return true
                return try {
                    release.await()
                    false
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } finally {
                    finished.countDown()
                }
            }

            override fun next(): RunEvent = events[nextEventIndex++]
        }
    }

    override fun close() {
        release.countDown()
        if (closeCount.incrementAndGet() == 1) closed.countDown()
    }
}

internal class ReconciliationBlockingContainsMap<K, V>(
    private val delegate: MutableMap<K, V>,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : AbstractMutableMap<K, V>() {
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = delegate.entries

    override fun put(
        key: K,
        value: V,
    ): V? = delegate.put(key, value)

    override fun containsKey(key: K): Boolean {
        entered.countDown()
        check(release.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "Blocked map lookup was not released."
        }
        return delegate.containsKey(key)
    }
}

internal class ReconciliationBlockingRecoveryRegistry : RunRecoveryRegistry {
    private val entries = mutableListOf<RunRecoveryEntry>()
    val loadStarted = CountDownLatch(1)
    val loadFinished = CountDownLatch(1)
    val releaseLoad = CountDownLatch(1)

    @Volatile
    var blockLoads = false

    @Volatile
    var blockSaves = false

    @Volatile
    var blockRemovals = false

    val saveStarted = CountDownLatch(1)
    val releaseSave = CountDownLatch(1)
    val removeRequests = AtomicInteger(0)
    val releaseRemove = CountDownLatch(1)

    override fun load(): List<RunRecoveryEntry> {
        val shouldSignalCompletion = blockLoads
        if (shouldSignalCompletion) {
            loadStarted.countDown()
            check(releaseLoad.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Recovery load was not released."
            }
        }
        return synchronized(entries) { entries.toList() }.also {
            if (shouldSignalCompletion) loadFinished.countDown()
        }
    }

    override fun save(entry: RunRecoveryEntry) {
        if (blockSaves) {
            saveStarted.countDown()
            check(releaseSave.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Recovery save was not released."
            }
        }
        synchronized(entries) {
            if (entry !in entries) entries += entry
        }
    }

    override fun remove(entry: RunRecoveryEntry) {
        removeRequests.incrementAndGet()
        if (blockRemovals) {
            check(releaseRemove.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Recovery removal was not released."
            }
        }
        synchronized(entries) { entries -= entry }
    }
}

internal fun privateField(
    holder: EntryStateHolder,
    name: String,
    replacement: Any? = null,
): Any {
    val field = EntryStateHolder::class.java.getDeclaredField(name).apply { isAccessible = true }
    if (replacement != null) field.set(holder, replacement)
    return field.get(holder)
}

internal fun invokeHistoryRunIdToReconcile(
    holder: EntryStateHolder,
    sessionId: SessionId,
    openedSession: org.hermesnative.client.feature.entry.application.OpenedSession,
): RunId? {
    return holder.historyRunIdToReconcile(sessionId, openedSession)
}

internal class ReconciliationUncertaintyStorage : RunSubmissionUncertaintyStorage {
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

internal class ReconciliationPausingDispatcher(
    private val delegate: CoroutineDispatcher? = null,
) : CoroutineDispatcher() {
    @Volatile
    var paused = false

    private val queued = ArrayDeque<Runnable>()
    val queuedCount: Int get() = synchronized(queued) { queued.size }

    override fun dispatch(
        context: kotlin.coroutines.CoroutineContext,
        block: Runnable,
    ) {
        if (paused) {
            synchronized(queued) { queued.addLast(block) }
        } else if (delegate != null) {
            delegate.dispatch(context, block)
        } else {
            block.run()
        }
    }

    fun runNext() = synchronized(queued) { queued.removeFirst() }.run()

    fun runLast() = synchronized(queued) { queued.removeLast() }.run()

    fun drain() {
        repeat(100) {
            if (queuedCount == 0) return
            runNext()
        }
        error("Dispatcher did not become idle.")
    }
}

internal class ReconciliationDelayedUnwindObservation : RunEventObservation {
    val started = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val closeCount = AtomicInteger(0)

    override fun iterator(): Iterator<RunEvent> {
        started.countDown()
        return object : Iterator<RunEvent> {
            override fun hasNext(): Boolean {
                return try {
                    awaitRelease()
                    false
                } catch (error: InterruptedException) {
                    Thread.interrupted()
                    hasNext()
                } finally {
                    finished.countDown()
                }
            }

            override fun next(): RunEvent = error("not used")
        }
    }

    override fun close() {
        if (closeCount.incrementAndGet() == 1) {
            closed.countDown()
        }
    }

    private fun awaitRelease() {
        var released = release.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        while (!released) {
            released = release.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }
}

internal const val RECONCILIATION_TIMEOUT_MILLIS = 5_000L
internal const val RECONCILIATION_SETTLE_MILLIS = 500L
