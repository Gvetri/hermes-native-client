package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.fixture.DeterministicGatewayFixture
import org.hermesnative.client.fixture.GatewayProcessFactory
import org.hermesnative.client.fixture.LocalSyntheticGatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EntryStateHolderFixtureIntegrationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )
    private val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")
    private val requiredCapabilities = PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers

    @Test
    fun state_holder_deduplicates_overlapping_fixture_pages_without_loading_history() {
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions =
                    listOf(
                        session(PINNED_A, "Pinned A", pinned = true),
                        session(SERVER_A, "Server A"),
                        session(SERVER_A, "Server A refreshed"),
                        session(SERVER_B, "Server B"),
                    ),
            ).apply {
                sessionPageSize = 2
            }

        fixture(behavior).execute { context ->
            val client =
                DefaultGatewayClient(
                    endpoint = "https://127.0.0.1:${context.endpoint.port}",
                    bearerToken = "fixture-only-token",
                    transport = LoopbackFixtureTransport(),
                )
            behavior.requests.clear()
            val holder =
                EntryStateHolder(
                    initialState = EntryState(isGatewayConnectionConfigured = false),
                    verifyGatewayConnection =
                        VerifyGatewayConnection(FakeGatewayConnectionRepository()) { _, _ ->
                            GatewayCapabilities(requiredCapabilities)
                        },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    sessionGatewayFactory = { _, _ -> client },
                )
            try {
                connect(holder)
                holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)

                val sessionList = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(
                    listOf(PINNED_A, SERVER_A, SERVER_B),
                    sessionList.sessions.map { it.id.value },
                )
                assertEquals("Server A refreshed", sessionList.sessions[1].title)
                assertEquals(
                    listOf(
                        "limit=20",
                        "limit=20&cursor=offset%3A2",
                    ),
                    behavior.requests.filter { it.path == "/v1/sessions" }.map { it.query },
                )
                assertTrue(behavior.requests.none { it.path.endsWith("/history") })
            } finally {
                holder.close()
            }
        }
    }

    private fun connect(holder: EntryStateHolder) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    }

    private fun session(
        id: String,
        title: String,
        pinned: Boolean = false,
    ): SyntheticGatewaySession =
        SyntheticGatewaySession(
            id = id,
            title = title,
            preview = "$title preview",
            pinned = pinned,
            updatedAt = "2026-09-08T20:00:00Z",
        )

    private fun fixture(behavior: SyntheticGatewayBehavior): DeterministicGatewayFixture =
        DeterministicGatewayFixture(
            descriptorFile = descriptorFile,
            processFactory =
                GatewayProcessFactory { descriptor ->
                    LocalSyntheticGatewayProcess.start(descriptor, behavior)
                },
        )

    private class FakeGatewayConnectionRepository : GatewayConnectionRepository {
        override fun load(): GatewayConnection? = null

        override fun save(connection: GatewayConnection) = Unit
    }

    private class LoopbackFixtureTransport : GatewayTransport {
        private val delegate = OkHttpGatewayTransport()

        override fun execute(request: GatewayHttpRequest): GatewayHttpResponse = delegate.execute(toLoopbackRequest(request))

        override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream = delegate.openEventStream(toLoopbackRequest(request))

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

    private companion object {
        const val PINNED_A = "11111111-1111-4111-8111-111111111111"
        const val SERVER_A = "33333333-3333-4333-8333-333333333333"
        const val SERVER_B = "44444444-4444-4444-8444-444444444444"
    }
}
