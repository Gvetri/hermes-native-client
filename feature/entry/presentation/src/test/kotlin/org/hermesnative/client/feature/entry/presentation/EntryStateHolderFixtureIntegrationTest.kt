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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun confirmed_unpin_reloads_terminal_first_page_to_apply_gateway_order() {
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions =
                    listOf(
                        session(PINNED_A, "Pinned A", pinned = true),
                        session(SERVER_B, "Server B"),
                    ),
            )

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                behavior.sessions.clear()
                behavior.sessions += session(SERVER_B, "Server B")
                behavior.sessions += session(PINNED_A, "Pinned A", pinned = true)
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(SessionId(PINNED_A)))

                val unpinned = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(
                    listOf(SERVER_B, PINNED_A),
                    unpinned.sessions.map { it.id.value },
                )
                assertFalse(unpinned.sessions.last().pinned)
                assertEquals(
                    listOf("limit=20"),
                    behavior.requests.filter { it.path == "/v1/sessions" }.map { it.query },
                )
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun real_gateway_rename_failure_preserves_title_and_retries_with_confirmed_result() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions = listOf(session(SERVER_A, "Original")),
            ).apply {
                failNextSessionRename = true
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Confirmed title"))
                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                val failed = requireNotNull(holder.uiState.value.sessionList)
                assertEquals("Original", failed.sessions.single().title)
                assertEquals(
                    SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                    failed.sessionMutations[targetId]?.rename?.errorCategory,
                )
                assertEquals(SessionMutationAction.RENAME, failed.sessionMutations[targetId]?.retryAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                val confirmed = requireNotNull(holder.uiState.value.sessionList)
                assertEquals("Confirmed title", confirmed.sessions.single().title)
                assertTrue(confirmed.sessionMutations.isEmpty())
                assertEquals(
                    2,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun real_fixture_blocks_duplicate_rename_submission_while_the_first_request_is_pending() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions = listOf(session(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "PATCH" && it.url.endsWith("/v1/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Renamed"))
                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                assertTrue(transport.started.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.RENAME, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )
                assertEquals("Original", requireNotNull(holder.uiState.value.sessionList).sessions.single().title)
                transport.release.countDown()
                awaitState(holder) { state ->
                    state.sessionList?.let { list ->
                        list.sessions.singleOrNull()?.title == "Renamed" &&
                            list.sessionMutations.isEmpty()
                    } == true
                }
            } finally {
                transport.release.countDown()
                holder.close()
            }
        }
    }

    @Test
    fun real_fixture_blocks_duplicate_pin_submission_while_the_first_request_is_pending() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions = listOf(session(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "POST" && it.url.endsWith("/v1/sessions/$SERVER_A/pin")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))

                assertTrue(transport.started.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.PIN, requireNotNull(mutation(holder, targetId)).pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "POST" && it.path == "/v1/sessions/$SERVER_A/pin"
                    },
                )

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "POST" && it.path == "/v1/sessions/$SERVER_A/pin"
                    },
                )
                transport.release.countDown()
                awaitState(holder) { state ->
                    state.sessionList?.let { list ->
                        list.sessions.singleOrNull()?.pinned == true &&
                            list.sessionMutations.isEmpty() &&
                            !list.isRefreshing
                    } == true
                }
            } finally {
                transport.release.countDown()
                holder.close()
            }
        }
    }

    @Test
    fun real_fixture_blocks_duplicate_unpin_submission_while_the_first_request_is_pending() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions = listOf(session(SERVER_A, "Original", pinned = true)),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "DELETE" && it.url.endsWith("/v1/sessions/$SERVER_A/pin")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))

                assertTrue(transport.started.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.UNPIN, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/v1/sessions/$SERVER_A/pin"
                    },
                )

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/v1/sessions/$SERVER_A/pin"
                    },
                )
                transport.release.countDown()
                awaitState(holder) { state ->
                    state.sessionList?.let { list ->
                        list.sessions.singleOrNull()?.pinned == false &&
                            list.sessionMutations.isEmpty() &&
                            !list.isRefreshing
                    } == true
                }
            } finally {
                transport.release.countDown()
                holder.close()
            }
        }
    }

    @Test
    fun real_fixture_blocks_duplicate_delete_submission_while_the_first_request_is_pending() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = requiredCapabilities + "client-manifest",
                initialSessions = listOf(session(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "DELETE" && it.url.endsWith("/v1/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.DeleteSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))

                assertTrue(transport.started.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.DELETE, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/v1/sessions/$SERVER_A"
                    },
                )
                transport.release.countDown()
                awaitState(holder) { state ->
                    state.sessionList?.let { list ->
                        list.sessions.isEmpty() && list.sessionMutations.isEmpty()
                    } == true
                }
            } finally {
                transport.release.countDown()
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
                        session(PINNED_A, "Already pinned", pinned = true),
                        session(SERVER_A, "Original", pinned = false),
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

    private fun client(
        context: FixtureTestContext,
        transport: GatewayTransport = LoopbackFixtureTransport(),
    ): DefaultGatewayClient =
        DefaultGatewayClient(
            endpoint = "https://127.0.0.1:${context.endpoint.port}",
            bearerToken = "fixture-only-token",
            transport = transport,
        )

    private fun stateHolder(
        client: DefaultGatewayClient,
        asynchronous: Boolean = false,
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(FakeGatewayConnectionRepository()) { _, _ ->
                    GatewayCapabilities(requiredCapabilities)
                },
            scope =
                CoroutineScope(
                    SupervisorJob() + if (asynchronous) Dispatchers.Default else Dispatchers.Unconfined,
                ),
            sessionGatewayFactory = { _, _ -> client },
        )

    private fun connect(holder: EntryStateHolder) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    }

    private fun mutation(
        holder: EntryStateHolder,
        sessionId: SessionId,
    ): SessionMutationUiState? = requireNotNull(holder.uiState.value.sessionList).sessionMutations[sessionId]

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (EntryUiState) -> Boolean,
    ) {
        runBlocking {
            withTimeout(ASYNC_TEST_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        }
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

    private class BlockingMutationResponseTransport(
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

        override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream = delegate.openEventStream(request)
    }

    private companion object {
        const val ASYNC_TEST_TIMEOUT_MILLIS = 5_000L
        const val PINNED_A = "11111111-1111-4111-8111-111111111111"
        const val SERVER_A = "33333333-3333-4333-8333-333333333333"
        const val SERVER_B = "44444444-4444-4444-8444-444444444444"
    }
}
