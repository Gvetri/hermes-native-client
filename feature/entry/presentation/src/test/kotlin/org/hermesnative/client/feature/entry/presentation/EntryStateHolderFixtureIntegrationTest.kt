package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayClient
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class EntryStateHolderFixtureIntegrationTest {
    @Test
    fun state_holder_deduplicates_overlapping_fixture_pages_without_loading_history() {
        val behavior =
            SyntheticGatewayBehavior(
                initialSessions =
                    listOf(
                        fixtureSession(PINNED_A, "Pinned A", pinned = true),
                        fixtureSession(SERVER_A, "Server A"),
                        fixtureSession(SERVER_A, "Server A refreshed"),
                        fixtureSession(SERVER_B, "Server B"),
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
                        VerifyGatewayConnection(FixtureGatewayConnectionRepository()) { _, _ ->
                            GatewayCapabilities(clientManifestEndpoints())
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
                        "limit=20&offset=0",
                        "limit=20&offset=2",
                    ),
                    behavior.requests.filter { it.path == "/api/sessions" }.map { it.query },
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
                initialSessions =
                    listOf(
                        fixtureSession(SERVER_A, "Server A"),
                        fixtureSession(SERVER_B, "Server B"),
                    ),
            ).apply {
                sessionPageSize = 1
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                assertEquals(
                    listOf(SERVER_A),
                    requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id.value },
                )

                holder.onEvent(EntryUiEvent.PinSessionClicked(SessionId(SERVER_A)))

                val pinned = requireNotNull(holder.uiState.value.sessionList)
                assertTrue(pinned.sessions.single().pinned)
                assertEquals(1, pinned.nextOffset)
                assertEquals(
                    listOf("limit=20&offset=0", "limit=20&offset=0"),
                    behavior.requests.filter { it.path == "/api/sessions" }.map { it.query },
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
                initialSessions =
                    listOf(
                        fixtureSession(PINNED_A, "Pinned A", pinned = true),
                        fixtureSession(SERVER_B, "Server B"),
                    ),
            )

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                behavior.sessions.clear()
                behavior.sessions += fixtureSession(SERVER_B, "Server B")
                behavior.sessions += fixtureSession(PINNED_A, "Pinned A", pinned = true)
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(SessionId(PINNED_A)))

                val unpinned = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(
                    listOf(SERVER_B, PINNED_A),
                    unpinned.sessions.map { it.id.value },
                )
                assertFalse(unpinned.sessions.last().pinned)
                assertEquals(
                    listOf("limit=20&offset=0"),
                    behavior.requests.filter { it.path == "/api/sessions" }.map { it.query },
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
                initialSessions = listOf(fixtureSession(SERVER_A, "Original")),
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
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                val confirmed = requireNotNull(holder.uiState.value.sessionList)
                assertEquals("Confirmed title", confirmed.sessions.single().title)
                assertTrue(confirmed.sessionMutations.isEmpty())
                assertEquals(
                    2,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
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
                initialSessions = listOf(fixtureSession(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "PATCH" && it.url.endsWith("/api/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitFixtureState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Renamed"))
                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                assertTrue(transport.started.await(FIXTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.RENAME, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmRenameSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )
                assertEquals("Original", requireNotNull(holder.uiState.value.sessionList).sessions.single().title)
                transport.release.countDown()
                awaitFixtureState(holder) { state ->
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
                initialSessions = listOf(fixtureSession(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "PATCH" && it.url.endsWith("/api/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitFixtureState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))

                assertTrue(transport.started.await(FIXTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.PIN, requireNotNull(mutation(holder, targetId)).pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.PinSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )
                transport.release.countDown()
                awaitFixtureState(holder) { state ->
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
                initialSessions = listOf(fixtureSession(SERVER_A, "Original", pinned = true)),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "PATCH" && it.url.endsWith("/api/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitFixtureState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))

                assertTrue(transport.started.await(FIXTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.UNPIN, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.UnpinSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "PATCH" && it.path == "/api/sessions/$SERVER_A"
                    },
                )
                transport.release.countDown()
                awaitFixtureState(holder) { state ->
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
                initialSessions = listOf(fixtureSession(SERVER_A, "Original")),
            )
        val transport =
            BlockingMutationResponseTransport {
                it.method == "DELETE" && it.url.endsWith("/api/sessions/$SERVER_A")
            }

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context, transport), asynchronous = true)
            try {
                connect(holder)
                awaitFixtureState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == targetId }
                behavior.requests.clear()

                holder.onEvent(EntryUiEvent.DeleteSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))

                assertTrue(transport.started.await(FIXTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(SessionMutationAction.DELETE, mutation(holder, targetId)?.pendingAction)
                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/api/sessions/$SERVER_A"
                    },
                )

                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))

                assertEquals(
                    1,
                    behavior.requests.count {
                        it.method == "DELETE" && it.path == "/api/sessions/$SERVER_A"
                    },
                )
                transport.release.countDown()
                awaitFixtureState(holder) { state ->
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
                initialSessions =
                    listOf(
                        fixtureSession(PINNED_A, "Already pinned", pinned = true),
                        fixtureSession(SERVER_A, "Original", pinned = false),
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

                assertDeleteRetryKeepsTheSession(holder, targetId)

                holder.onEvent(EntryUiEvent.ConfirmDeleteSessionClicked(targetId))
                assertEquals(
                    listOf(PINNED_A),
                    requireNotNull(holder.uiState.value.sessionList).sessions.map { it.id.value },
                )
                assertTrue(behavior.sessions.none { it.id == SERVER_A })
                assertEquals(
                    listOf("PATCH", "PATCH", "PATCH", "PATCH", "PATCH", "DELETE", "DELETE"),
                    behavior.requests.filter { it.path.contains(SERVER_A) }.map { it.method },
                )
            } finally {
                holder.close()
            }
        }
    }
}
