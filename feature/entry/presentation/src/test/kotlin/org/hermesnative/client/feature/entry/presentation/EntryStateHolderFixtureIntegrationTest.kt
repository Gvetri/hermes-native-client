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
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.DeterministicGatewayFixture
import org.hermesnative.client.fixture.FixtureTestContext
import org.hermesnative.client.fixture.GatewayProcessFactory
import org.hermesnative.client.fixture.LocalSyntheticGatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun confirmed_pin_reloads_the_first_page_before_loading_more_from_a_changed_server_order() {
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions =
                    listOf(
                        session(SERVER_A, "Server A"),
                        session(SERVER_B, "Server B"),
                    ),
            ).apply {
                sessionPageSize = 1
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                assertEquals(listOf(SERVER_A), requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id.value })

                holder.onEvent(EntryUiEvent.PinSessionClicked(SessionId(SERVER_A)))

                val pinned = requireNotNull(holder.uiState.value.sessionList)
                assertTrue(pinned.sessions.single().pinned)
                assertEquals("offset:1", pinned.nextCursor)
                assertEquals(
                    listOf("limit=20", "limit=20"),
                    behavior.requests.filter { it.path == "/v1/sessions" }.map { it.query },
                )
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun state_holder_applies_confirmed_real_gateway_mutations_and_retries_failures_without_local_optimism() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions =
                    listOf(
                        session(SERVER_A, "Original", pinned = false),
                        session(PINNED_A, "Already pinned", pinned = true),
                    ),
            ).apply {
                failNextSessionPin = true
                failNextSessionUnpin = true
                failNextSessionDelete = true
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                val initial = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(listOf(PINNED_A, SERVER_A), initial.sessions.map { it.id.value })

                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Confirmed title"))
                assertEquals("Original", requireNotNull(holder.uiState.value.sessionList).sessions.last().title)
                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))
                assertEquals("Confirmed title", requireNotNull(holder.uiState.value.sessionList).sessions.last().title)

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))
                val pinFailure = requireNotNull(holder.uiState.value.sessionList)
                assertFalse(pinFailure.sessions.single { it.id == targetId }.pinned)
                assertEquals(SessionMutationAction.PIN, pinFailure.sessionMutations[targetId]?.retryAction)

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))
                val pinned = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(listOf(PINNED_A, SERVER_A), pinned.sessions.map { it.id.value })
                assertTrue(pinned.sessions.all { it.pinned })

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))
                val unpinFailure = requireNotNull(holder.uiState.value.sessionList)
                assertTrue(unpinFailure.sessions.single { it.id == targetId }.pinned)
                assertEquals(SessionMutationAction.UNPIN, unpinFailure.sessionMutations[targetId]?.retryAction)

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))
                val unpinned = requireNotNull(holder.uiState.value.sessionList)
                assertFalse(unpinned.sessions.single { it.id == targetId }.pinned)

                holder.onEvent(EntryUiEvent.DeleteSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))
                val deleteFailure = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(listOf(PINNED_A, SERVER_A), deleteFailure.sessions.map { it.id.value })
                assertEquals(SessionMutationAction.DELETE, deleteFailure.sessionMutations[targetId]?.retryAction)

                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))
                assertEquals(listOf(PINNED_A), requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id.value })
                assertTrue(behavior.sessions.none { it.id == SERVER_A })
                assertEquals(
                    listOf("PATCH", "POST", "POST", "DELETE", "DELETE", "DELETE", "DELETE"),
                    behavior.requests.filter { it.path.contains(SERVER_A) }.map { it.method },
                )
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun refresh_replaces_real_gateway_metadata_preserves_only_an_unsent_draft_and_recovers_from_remote_deletion() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions =
                    listOf(
                        session(SERVER_A, "Listed title", pinned = false),
                        session(SERVER_B, "Removed on refresh", pinned = false),
                    ),
            )

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Unsent draft"))

                behavior.sessions.clear()
                behavior.sessions += session(SERVER_A, "Server replacement", pinned = true, preview = "Server preview")
                holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

                val refreshed = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(listOf(SERVER_A), refreshed.sessions.map { it.id.value })
                assertEquals("Server replacement", refreshed.sessions.single().title)
                assertEquals("Server preview", refreshed.sessions.single().preview)
                assertTrue(refreshed.sessions.single().pinned)
                assertEquals("Unsent draft", refreshed.sessionMutations[targetId]?.rename?.titleDraft)
                assertFalse(refreshed.isStale)
                assertFalse(refreshed.isUnavailable)

                behavior.sessions.clear()
                holder.onEvent(EntryUiEvent.SessionClicked(targetId))
                val deleted = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, deleted.errorCategory)
                assertTrue(deleted.isUnavailable)
                assertEquals(listOf(SERVER_A), deleted.sessions.map { it.id.value })
                assertEquals("Unsent draft", deleted.sessionMutations[targetId]?.rename?.titleDraft)

                holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
                val recovered = requireNotNull(holder.uiState.value.sessionList)
                assertTrue(recovered.sessions.isEmpty())
                assertTrue(recovered.sessionMutations.isEmpty())
                assertFalse(recovered.isStale)
                assertFalse(recovered.isUnavailable)
                assertEquals(null, recovered.errorCategory)
            } finally {
                holder.close()
            }
        }
    }

    private fun client(context: FixtureTestContext): DefaultGatewayClient =
        DefaultGatewayClient(
            endpoint = "https://127.0.0.1:${context.endpoint.port}",
            bearerToken = "fixture-only-token",
            transport = LoopbackFixtureTransport(),
        )

    private fun stateHolder(client: DefaultGatewayClient): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(FakeGatewayConnectionRepository()) { _, _ ->
                    GatewayCapabilities(requiredCapabilities)
                },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionGatewayFactory = { _, _ -> client },
        )

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
        preview: String = "$title preview",
    ): SyntheticGatewaySession =
        SyntheticGatewaySession(
            id = id,
            title = title,
            preview = preview,
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
