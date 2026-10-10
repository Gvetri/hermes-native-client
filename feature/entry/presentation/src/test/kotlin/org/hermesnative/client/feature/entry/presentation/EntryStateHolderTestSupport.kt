package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val ASYNC_TEST_TIMEOUT_MILLIS = 5_000L

internal fun clientManifestCapabilities(): GatewayCapabilities =
    GatewayCapabilities(
        PublicBetaGatewayCapabilityManifest.current.requiredEndpoints,
    )

internal fun holder(
    repository: FakeGatewayConnectionRepository,
    verifier: (String, String) -> GatewayCapabilities,
    gateway: SessionGatewayPort? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): EntryStateHolder =
    EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection = VerifyGatewayConnection(repository, discoverCapabilities = verifier),
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        sessionGatewayFactory =
            gateway?.let { sessionGateway ->
                { _, _ -> sessionGateway }
            },
    )

internal fun verify(holder: EntryStateHolder) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
}

internal fun blockingCreationHolder(gateway: BlockingCreateGateway): EntryStateHolder =
    EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(
                FakeGatewayConnectionRepository(),
            ) { _, _ ->
                clientManifestCapabilities()
            },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        sessionGatewayFactory = { _, _ -> gateway },
    )

internal fun slowHolder(
    gateway: SessionGatewayPort,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): EntryStateHolder =
    EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(
                FakeGatewayConnectionRepository(),
            ) { _, _ ->
                clientManifestCapabilities()
            },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        sessionGatewayFactory = { _, _ -> gateway },
    )

internal fun verifySlow(
    holder: EntryStateHolder,
    gateway: SlowSessionGateway,
) {
    verify(holder)
    gateway.awaitRequest(SessionListRequest())
    awaitState(holder, "initial Session list") {
        it.sessionList?.sessions?.isNotEmpty() == true
    }
}

internal fun awaitSessions(
    holder: EntryStateHolder,
    vararg ids: String,
) {
    awaitState(holder, "Sessions ${ids.toList()}") {
        it.sessionList?.sessions?.map { session -> session.id.value } == ids.toList()
    }
}

internal fun awaitState(
    holder: EntryStateHolder,
    description: String,
    predicate: (EntryUiState) -> Boolean,
) {
    try {
        runBlocking {
            withTimeout(ASYNC_TEST_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw AssertionError("Timed out waiting for $description.")
    }
}

internal fun entrySession(
    id: String,
    title: String? = null,
    pinned: Boolean = false,
): Session =
    Session(
        id = SessionId(id),
        title = title,
        preview = "Server preview",
        pinned = pinned,
    )

internal class FakeGatewayConnectionRepository : GatewayConnectionRepository {
    var saved: GatewayConnection? = null
    var throwOnSave = false

    override fun load(): GatewayConnection? = saved

    override fun save(connection: GatewayConnection) {
        if (throwOnSave) error("storage failure")
        saved = connection
    }
}

internal class FakeSessionGateway : SessionGatewayPort {
    private val listResults = ArrayDeque<Result<SessionPage>>()
    private val createResults = ArrayDeque<Result<Session>>()
    val listRequests = mutableListOf<SessionListRequest>()
    val operations = mutableListOf<String>()
    val createTitles = mutableListOf<String?>()
    var createCalls = 0
    var openedSession: Session? = null
    var openedHistory: SessionHistory? = null
    var openFailure: GatewayException? = null

    fun enqueueList(page: SessionPage) {
        listResults += Result.success(page)
    }

    fun enqueueFailure(error: GatewayException) {
        listResults += Result.failure(error)
    }

    fun enqueueCreate(session: Session) {
        createResults += Result.success(session)
    }

    fun enqueueCreateFailure(error: GatewayException) {
        createResults += Result.failure(error)
    }

    override fun listSessions(request: SessionListRequest): SessionPage {
        listRequests += request
        return listResults.removeFirst().getOrThrow()
    }

    override fun createSession(title: String?): Session {
        createCalls += 1
        createTitles += title
        operations += "create"
        return createResults.removeFirst().getOrThrow()
    }

    override fun openSession(sessionId: SessionId): Session {
        operations += "open"
        openFailure?.let { throw it }
        return requireNotNull(openedSession)
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        operations += "history"
        return requireNotNull(openedHistory)
    }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId): Unit = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")
}

internal class BlockingCreateGateway(
    private val initialSession: Session,
    private val createdSession: Session,
) : SessionGatewayPort {
    private val createResult = CompletableFuture<Session>()
    val createStarted = CountDownLatch(1)
    val listRequests = CopyOnWriteArrayList<SessionListRequest>()
    private var created = false

    override fun listSessions(request: SessionListRequest): SessionPage {
        listRequests += request
        return SessionPage(listOf(if (created) createdSession else initialSession), null)
    }

    override fun createSession(title: String?): Session {
        createStarted.countDown()
        return createResult.get(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).also {
            created = true
        }
    }

    override fun openSession(sessionId: SessionId): Session {
        check(sessionId == createdSession.id) { "Unexpected Session open: $sessionId" }
        return createdSession
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        return SessionHistory(sessionId, emptyList<GatewayHistoryMessage>())
    }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId): Unit = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

    fun completeCreate() {
        createResult.complete(createdSession)
    }
}

internal class BlockingSessionGateway(
    private val blockHistoryRefresh: Boolean = false,
    private val blockListRefresh: Boolean = false,
    private val nextPageOffset: Int? = null,
) : SessionGatewayPort {
    val sessionId = SessionId("session-one")
    val refreshStarted = CountDownLatch(1)
    val historyRefreshStarted = CountDownLatch(1)
    val releaseRefresh = CountDownLatch(1)
    val listRefreshStarted = CountDownLatch(1)
    val releaseListRefresh = CountDownLatch(1)
    val pinStarted = CountDownLatch(1)
    var pinCalls = 0
    var historyCalls = 0
    private var listCalls = 0
    private val listedSession =
        Session(
            id = sessionId,
            title = "Session",
            preview = "Preview",
            pinned = false,
        )

    override fun listSessions(request: SessionListRequest): SessionPage {
        val call =
            synchronized(this) {
                listCalls += 1
                listCalls
            }
        if (blockListRefresh && call > 1) {
            listRefreshStarted.countDown()
            awaitRelease(releaseListRefresh, "list refresh")
        }
        return SessionPage(listOf(listedSession), nextPageOffset)
    }

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session {
        check(sessionId == this.sessionId) { "Unexpected Session open: $sessionId" }
        return listedSession
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        check(sessionId == this.sessionId) { "Unexpected Session history: $sessionId" }
        val call =
            synchronized(this) {
                historyCalls += 1
                historyCalls
            }
        if (call > 1) {
            historyRefreshStarted.countDown()
        }
        if (blockHistoryRefresh && call > 1) {
            refreshStarted.countDown()
            awaitRelease(releaseRefresh, "history refresh")
        }
        return SessionHistory(
            sessionId = this.sessionId,
            messages = listOf(GatewayHistoryMessage("message-$call", null, null)),
        )
    }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId): Unit = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult {
        check(sessionId == this.sessionId) { "Unexpected Session pin: $sessionId" }
        synchronized(this) {
            pinCalls += 1
        }
        pinStarted.countDown()
        return SessionPinResult(sessionId, pinned = true)
    }

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

    private fun awaitRelease(
        latch: CountDownLatch,
        operation: String,
    ) {
        if (!latch.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw AssertionError("Timed out waiting for $operation release.")
        }
    }
}

internal class SlowSessionGateway(
    private val initialPage: SessionPage,
) : SessionGatewayPort {
    val listRequests = CopyOnWriteArrayList<SessionListRequest>()
    private val requestMonitor = Object()
    private val requestGates = mutableMapOf<SessionListRequest, MutableList<RequestGate>>()
    private val beforeReturnActions = mutableMapOf<RequestKey, () -> Unit>()

    override fun listSessions(request: SessionListRequest): SessionPage {
        val (occurrence, gate) =
            synchronized(requestMonitor) {
                val gates = requestGates.getOrPut(request) { mutableListOf() }
                val occurrence = gates.size
                val gate = RequestGate()
                gates += gate
                listRequests += request
                requestMonitor.notifyAll()
                occurrence to gate
            }
        if (request == SessionListRequest() && occurrence == 0) return initialPage
        val result =
            try {
                gate.result.get(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                throw AssertionError(
                    "Timed out waiting for SessionPage for request $request occurrence $occurrence.",
                )
            }
        synchronized(requestMonitor) {
            beforeReturnActions.remove(RequestKey(request, occurrence))?.invoke()
        }
        return result
    }

    fun awaitRequest(
        request: SessionListRequest,
        occurrence: Int = 0,
    ) {
        awaitGate(request, occurrence)
    }

    fun beforeReturn(
        request: SessionListRequest,
        occurrence: Int = 0,
        action: () -> Unit,
    ) {
        synchronized(requestMonitor) {
            beforeReturnActions[RequestKey(request, occurrence)] = action
        }
    }

    fun complete(
        request: SessionListRequest,
        page: SessionPage,
        occurrence: Int = 0,
    ) {
        val gate = awaitGate(request, occurrence)
        gate.result.complete(page)
    }

    private fun awaitGate(
        request: SessionListRequest,
        occurrence: Int,
    ): RequestGate =
        synchronized(requestMonitor) {
            val deadline =
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ASYNC_TEST_TIMEOUT_MILLIS)
            while (requestGates[request].orEmpty().size <= occurrence) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    throw AssertionError(
                        "Timed out waiting for request $request occurrence $occurrence.",
                    )
                }
                TimeUnit.NANOSECONDS.timedWait(requestMonitor, remaining)
            }
            requestGates.getValue(request)[occurrence]
        }

    private data class RequestKey(
        val request: SessionListRequest,
        val occurrence: Int,
    )

    private class RequestGate {
        val result = CompletableFuture<SessionPage>()
    }

    override fun createSession(title: String?): Session = error("not used")

    override fun openSession(sessionId: SessionId): Session = error("not used")

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory = error("not used")

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session = error("not used")

    override fun deleteSession(sessionId: SessionId): Unit = error("not used")

    override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

    override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")
}
