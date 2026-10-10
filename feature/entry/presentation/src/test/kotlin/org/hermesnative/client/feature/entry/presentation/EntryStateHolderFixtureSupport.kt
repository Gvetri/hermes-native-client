package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayClient
import org.hermesnative.client.feature.entry.data.GatewayEventStream
import org.hermesnative.client.feature.entry.data.GatewayHttpRequest
import org.hermesnative.client.feature.entry.data.GatewayHttpResponse
import org.hermesnative.client.feature.entry.data.GatewayTransport
import org.hermesnative.client.feature.entry.data.OkHttpGatewayTransport
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayEndpoint
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.DeterministicGatewayFixture
import org.hermesnative.client.fixture.FixtureTestContext
import org.hermesnative.client.fixture.GatewayProcessFactory
import org.hermesnative.client.fixture.LocalSyntheticGatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

internal val repositoryRoot =
    File(
        requireNotNull(System.getProperty("fixture.repositoryRoot")) {
            "fixture.repositoryRoot must identify the repository root"
        },
    )
internal val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")

internal fun assertDeleteRetryKeepsTheSession(
    holder: EntryStateHolder,
    targetId: SessionId,
) {
    holder.onEvent(EntryUiEvent.DeleteSessionClicked(targetId))
    holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))
    val deleteFailure = requireNotNull(holder.uiState.value.sessionList)
    assertEquals(listOf(PINNED_A, SERVER_A), deleteFailure.sessions.map { it.id.value })
    assertEquals(SessionMutationAction.DELETE, deleteFailure.sessionMutations[targetId]?.retryAction)
}

internal fun clientManifestEndpoints(): Map<String, GatewayEndpoint> {
    return PublicBetaGatewayCapabilityManifest.current.requiredEndpoints
}

internal fun sessionRunsTrackedBy(
    holder: EntryStateHolder,
    sessionId: SessionId,
): List<Run> {
    val field = EntryStateHolder::class.java.getDeclaredField("sessionRuns").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    return (field.get(holder) as Map<SessionId, List<Run>>)[sessionId].orEmpty()
}

internal fun client(
    context: FixtureTestContext,
    transport: GatewayTransport = LoopbackFixtureTransport(),
): DefaultGatewayClient =
    DefaultGatewayClient(
        endpoint = "https://127.0.0.1:${context.endpoint.port}",
        bearerToken = "fixture-only-token",
        transport = transport,
    )

internal fun stateHolder(
    client: DefaultGatewayClient,
    asynchronous: Boolean = false,
    runGateway: RunGatewayPort? = null,
    recoveryRegistry: RunRecoveryRegistry? = null,
): EntryStateHolder =
    EntryStateHolder(
        initialState = EntryState(isGatewayConnectionConfigured = false),
        verifyGatewayConnection =
            VerifyGatewayConnection(FixtureGatewayConnectionRepository()) { _, _ ->
                GatewayCapabilities(clientManifestEndpoints())
            },
        scope =
            CoroutineScope(
                SupervisorJob() + if (asynchronous) Dispatchers.Default else Dispatchers.Unconfined,
            ),
        sessionGatewayFactory = { _, _ -> client },
        runGatewayFactory = runGateway?.let { gateway -> { _, _ -> gateway } },
        dependencies = EntryStateHolderDependencies(runRecoveryRegistry = recoveryRegistry),
    )

internal fun connect(holder: EntryStateHolder) {
    holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
    holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
    holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
    holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
}

internal fun mutation(
    holder: EntryStateHolder,
    sessionId: SessionId,
): SessionMutationUiState? = requireNotNull(holder.uiState.value.sessionList).sessionMutations[sessionId]

internal fun awaitFixtureState(
    holder: EntryStateHolder,
    predicate: (EntryUiState) -> Boolean,
) {
    runBlocking {
        withTimeout(FIXTURE_TIMEOUT_MILLIS) {
            holder.uiState.first(predicate)
        }
    }
}

internal fun fixtureSession(
    id: String,
    title: String,
    pinned: Boolean = false,
    preview: String = "$title preview",
): SyntheticGatewaySession =
    SyntheticGatewaySession(
        id = id,
        title = title,
        preview = preview,
        pinned = pinned,
    )

internal fun fixture(behavior: SyntheticGatewayBehavior): DeterministicGatewayFixture =
    DeterministicGatewayFixture(
        descriptorFile = descriptorFile,
        processFactory =
            GatewayProcessFactory { descriptor ->
                LocalSyntheticGatewayProcess.start(descriptor, behavior)
            },
    )

internal class FixtureRunGateway(
    private val statuses: Map<RunId, Run>,
    private val createdRun: Run? = null,
) : RunGatewayPort {
    val statusRequests = CopyOnWriteArrayList<RunId>()
    val createdRunRequests = CopyOnWriteArrayList<Pair<SessionId, String>>()

    override fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run {
        createdRunRequests += sessionId to input
        return requireNotNull(createdRun) { "Run creation is not configured for this fixture." }
    }

    override fun getRunStatus(runId: RunId): Run {
        statusRequests += runId
        return requireNotNull(statuses[runId]) { "No fixture status for $runId" }
    }

    override fun observeRun(runId: RunId): RunEventObservation = error("not used")
}

internal class FixtureGatewayConnectionRepository : GatewayConnectionRepository {
    override fun load(): GatewayConnection? = null

    override fun save(connection: GatewayConnection) = Unit
}

internal class LoopbackFixtureTransport : GatewayTransport {
    private val delegate = OkHttpGatewayTransport()

    override fun execute(request: GatewayHttpRequest): GatewayHttpResponse =
        delegate.execute(
            toLoopbackRequest(request),
        )

    override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream =
        delegate.openEventStream(
            toLoopbackRequest(request),
        )

    private fun toLoopbackRequest(request: GatewayHttpRequest): GatewayHttpRequest {
        val secureUrl = request.url.removePrefix("https://")
        require(secureUrl.startsWith("127.0.0.1:")) {
            "Fixture transport accepts only the loopback fixture endpoint."
        }
        return GatewayHttpRequest(
            method = request.method,
            url = "http://$secureUrl",
            headers = request.headers.filterKeys { it != "Authorization" },
            body = request.body,
        )
    }
}

internal class BlockingMutationResponseTransport(
    private val shouldBlock: (GatewayHttpRequest) -> Boolean,
) : GatewayTransport {
    private val delegate = LoopbackFixtureTransport()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun execute(request: GatewayHttpRequest): GatewayHttpResponse {
        val response = delegate.execute(request)
        if (shouldBlock(request)) {
            started.countDown()
            try {
                release.await()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
        return response
    }

    override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream =
        delegate.openEventStream(
            request,
        )
}

internal const val FIXTURE_TIMEOUT_MILLIS = 5_000L
internal const val PINNED_A = "11111111-1111-4111-8111-111111111111"
internal const val SERVER_A = "33333333-3333-4333-8333-333333333333"
internal const val SERVER_B = "44444444-4444-4444-8444-444444444444"
