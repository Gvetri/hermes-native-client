package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EntryStateHolderTest {
    @Test
    fun add_gateway_connection_event_requests_connection_setup() {
        val stateHolder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = false),
            )

        stateHolder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)

        assertTrue(stateHolder.uiState.value.connectionSetupRequested)
    }

    @Test
    fun secure_save_is_explicit_and_persists_the_credential_only_after_verification() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("secure-token"))
        holder.onEvent(EntryUiEvent.SaveCredentialChanged(true))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertTrue(holder.uiState.value.saveCredential)
        assertEquals("secure-token", repository.saved?.bearerCredential)
        holder.close()
    }

    @Test
    fun a_connected_user_can_rotate_the_credential_without_removing_the_gateway_connection() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("working-token"))
        holder.onEvent(EntryUiEvent.SaveCredentialChanged(true))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.ChangeGatewayCredentialClicked)
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("replacement-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(GatewayConnection("https://gateway.example/profile", "replacement-token"), repository.saved)
        assertTrue(holder.uiState.value.isConnected)
        assertFalse(holder.uiState.value.isChangingCredential)
        holder.close()
    }

    @Test
    fun failed_credential_rotation_preserves_the_working_credential_and_keeps_rotation_retryable() {
        val repository = FakeGatewayConnectionRepository()
        var verificationCount = 0
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    verificationCount += 1
                    if (verificationCount == 2) throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("working-token"))
        holder.onEvent(EntryUiEvent.SaveCredentialChanged(true))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.ChangeGatewayCredentialClicked)
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("replacement-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(EntryErrorCategory.AUTHENTICATION_FAILED, holder.uiState.value.errorCategory)
        assertEquals(GatewayConnection("https://gateway.example/profile", "working-token"), repository.saved)
        assertTrue(holder.uiState.value.isConnected)
        assertTrue(holder.uiState.value.isChangingCredential)
        holder.close()
    }

    @Test
    fun failed_credential_persistence_during_rotation_preserves_the_working_credential() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("working-token"))
        holder.onEvent(EntryUiEvent.SaveCredentialChanged(true))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        repository.throwOnSave = true
        holder.onEvent(EntryUiEvent.ChangeGatewayCredentialClicked)
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("replacement-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(EntryErrorCategory.CREDENTIAL_STORAGE_FAILED, holder.uiState.value.errorCategory)
        assertEquals(GatewayConnection("https://gateway.example/profile", "working-token"), repository.saved)
        assertTrue(holder.uiState.value.isConnected)
        assertTrue(holder.uiState.value.isChangingCredential)
        holder.close()
    }

    @Test
    fun a_restarted_state_prefills_an_opt_in_credential_without_defaulting_to_secure_save() {
        val holder =
            EntryStateHolder(
                EntryState(
                    isGatewayConnectionConfigured = true,
                    configuredEndpoint = "https://gateway.example/profile",
                    configuredCredential = "secure-token",
                ),
            )

        assertEquals("secure-token", holder.uiState.value.bearerCredential)
        assertTrue(holder.uiState.value.saveCredential)
        holder.close()
    }

    @Test
    fun secure_storage_failure_keeps_the_connection_disconnected_and_reports_a_safe_recovery_action() {
        val repository = FakeGatewayConnectionRepository().apply { throwOnSave = true }
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("secure-token"))
        holder.onEvent(EntryUiEvent.SaveCredentialChanged(true))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(EntryErrorCategory.CREDENTIAL_STORAGE_FAILED, holder.uiState.value.errorCategory)
        assertFalse(holder.uiState.value.isConnected)
        holder.close()
    }

    @Test
    fun verification_enters_loading_then_connected_and_persists_only_the_endpoint() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertFalse(holder.uiState.value.isVerifying)
        assertEquals("https://gateway.example/profile", repository.saved?.endpoint)
        holder.close()
    }

    @Test
    fun recoverable_failure_keeps_inputs_maps_invalid_response_to_a_safe_category_and_can_retry() {
        val repository = FakeGatewayConnectionRepository()
        var attempts = 0
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        throw GatewayException(GatewayErrorCategory.INVALID_RESPONSE)
                    }
                    clientManifestCapabilities()
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(EntryErrorCategory.GATEWAY_REQUEST_FAILED, holder.uiState.value.errorCategory)
        assertEquals("https://gateway.example/profile", holder.uiState.value.endpoint)
        assertEquals("memory-only-token", holder.uiState.value.bearerCredential)
        assertFalse(holder.uiState.value.isVerifying)

        holder.onEvent(EntryUiEvent.TryAgainClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertEquals(2, attempts)
        holder.close()
    }

    @Test
    fun removing_connection_while_verification_is_in_flight_cannot_restore_the_connection() {
        val repository = FakeGatewayConnectionRepository()
        val verificationStarted = CountDownLatch(1)
        val releaseVerification = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    verificationStarted.countDown()
                    assertTrue(
                        releaseVerification.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    )
                    clientManifestCapabilities()
                },
                gateway = FakeSessionGateway(),
                dispatcher = dispatcher,
            )

        try {
            verify(holder)
            assertTrue(verificationStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            releaseVerification.countDown()
            executor.submit {}.get(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

            assertFalse(holder.uiState.value.isConnected)
            assertNull(holder.uiState.value.sessionList)
        } finally {
            releaseVerification.countDown()
            holder.close()
            dispatcher.close()
        }
    }

    @Test
    fun successful_connection_loads_the_first_page_with_pinned_sessions_first() {
        val repository = FakeGatewayConnectionRepository()
        val gateway = FakeSessionGateway()
        val unpinnedOlder = entrySession("unpinned-older", pinned = false)
        val pinnedOlder = entrySession("pinned-older", pinned = true)
        val pinnedNewer = entrySession("pinned-newer", pinned = true)
        val unpinnedNewer = entrySession("unpinned-newer", pinned = false)
        gateway.enqueueList(
            SessionPage(
                sessions = listOf(unpinnedOlder, pinnedOlder, pinnedNewer, unpinnedNewer),
                nextOffset = 1,
            ),
        )
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)

        val sessionList = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(
            listOf("pinned-older", "pinned-newer", "unpinned-older", "unpinned-newer"),
            sessionList.sessions.map { it.id.value },
        )
        assertEquals("Untitled Session", sessionList.sessions.first().title)
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)
        assertTrue(sessionList.showFirstUseGuidance)

        holder.close()
    }

    @Test
    fun refresh_preserves_visible_sessions_when_unavailable_and_reloads_the_first_page_on_retry() {
        val gateway = FakeSessionGateway()
        val firstSession = entrySession("first", title = "First")
        val refreshedSession = entrySession("second", title = "Second")
        gateway.enqueueList(SessionPage(listOf(firstSession), 1))
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueList(SessionPage(listOf(refreshedSession), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val unavailable = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("first"), unavailable.sessions.map { it.id.value })
        assertEquals(1, unavailable.nextOffset)
        assertTrue(unavailable.isStale)
        assertFalse(unavailable.isUnavailable)
        assertEquals(SessionListErrorCategory.GATEWAY_UNAVAILABLE, unavailable.errorCategory)

        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val recovered = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("second"), recovered.sessions.map { it.id.value })
        assertFalse(recovered.isStale)
        assertFalse(recovered.isUnavailable)
        assertEquals(3, gateway.listRequests.size)
        assertTrue(gateway.listRequests.all { it == SessionListRequest() })
        holder.close()
    }

    @Test
    fun search_filters_server_provided_sessions_locally_without_issuing_a_request() {
        val gateway = FakeSessionGateway()
        val initial = entrySession("initial", title = "Initial")
        val matching = entrySession("matching", title = "Matching")
        val other = entrySession("other", title = "Other", pinned = false)
        gateway.enqueueList(SessionPage(listOf(initial, matching, other), nextOffset = 1))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("matching"))

        val searched = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("matching", searched.searchQuery)
        assertEquals(listOf("initial", "matching", "other"), searched.sessions.map { it.id.value })
        assertEquals(listOf("matching"), searched.visibleSessions.map { it.id.value })
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)

        holder.onEvent(EntryUiEvent.ClearSessionSearchClicked)

        val cleared = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("", cleared.searchQuery)
        assertEquals(listOf("initial", "matching", "other"), cleared.visibleSessions.map { it.id.value })
        assertEquals(1, gateway.listRequests.size)
        holder.close()
    }

    @Test
    fun rapid_search_query_changes_never_issue_a_gateway_request() {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("initial")), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("n"))
        holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("needle"))

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("needle", state.searchQuery)
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)
        holder.close()
    }

    @Test
    fun loading_more_uses_the_server_offset_merges_without_duplicates_and_refresh_resets_pagination() {
        val gateway = FakeSessionGateway()
        val pinned = entrySession("pinned", title = "Pinned", pinned = true)
        val first = entrySession("first", title = "First")
        val secondPinned = entrySession("second-pinned", title = "Second pinned", pinned = true)
        val refreshed = entrySession("refreshed", title = "Refreshed")
        gateway.enqueueList(SessionPage(listOf(first, pinned), nextOffset = 2))
        gateway.enqueueList(SessionPage(listOf(secondPinned, first), nextOffset = null))
        gateway.enqueueList(SessionPage(listOf(refreshed), nextOffset = 3))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)

        val paged = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(
            listOf("pinned", "second-pinned", "first"),
            paged.sessions.map { it.id.value },
        )
        assertEquals(SessionListRequest(offset = 2), gateway.listRequests[1])
        assertEquals(3, paged.sessions.size)
        assertEquals(null, paged.nextOffset)
        assertFalse(paged.isLoadingMore)

        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val reset = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("refreshed"), reset.sessions.map { it.id.value })
        assertEquals(3, reset.nextOffset)
        assertEquals(SessionListRequest(), gateway.listRequests.last())
        holder.close()
    }

    @Test
    fun duplicate_load_more_actions_are_ignored_while_the_page_is_in_flight() {
        val gateway = SlowSessionGateway(SessionPage(listOf(entrySession("first")), nextOffset = 1))
        val holder = slowHolder(gateway)
        verifySlow(holder, gateway)

        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)
        gateway.awaitRequest(SessionListRequest(offset = 1))
        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)

        assertEquals(
            listOf(SessionListRequest(), SessionListRequest(offset = 1)),
            gateway.listRequests,
        )
        assertTrue(requireNotNull(holder.uiState.value.sessionList).isLoadingMore)

        gateway.complete(SessionListRequest(offset = 1), SessionPage(listOf(entrySession("second")), null))
        awaitSessions(holder, "first", "second")
        holder.close()
    }

    @Test
    fun opening_during_refresh_is_rejected_so_the_refresh_state_can_complete() {
        val gateway = SlowSessionGateway(SessionPage(listOf(entrySession("target")), null))
        val holder = slowHolder(gateway)
        verifySlow(holder, gateway)

        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        gateway.awaitRequest(SessionListRequest(), occurrence = 1)
        assertTrue(requireNotNull(holder.uiState.value.sessionList).isRefreshing)

        holder.onEvent(EntryUiEvent.SessionClicked(SessionId("target")))
        gateway.complete(
            SessionListRequest(),
            SessionPage(listOf(entrySession("refreshed")), null),
            occurrence = 1,
        )
        awaitSessions(holder, "refreshed")

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertFalse(state.isRefreshing)
        assertFalse(state.isSearching)
        assertFalse(state.isLoading)
        assertNull(state.openedSession)
        holder.close()
    }

    @Test
    fun opening_a_session_during_local_search_opens_authoritative_history() {
        val sessionId = SessionId("target")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Target")), null))
        gateway.openedSession = entrySession(sessionId.value, "Target")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Found it")),
            )
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("targ"))
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        awaitState(holder, "opened search result") {
            it.sessionList?.openedSession?.session?.id == sessionId
        }
        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("targ", state.searchQuery)
        assertEquals(listOf("Found it"), state.openedSession?.messages?.map { it.content })
        holder.close()
    }

    @Test
    fun search_is_ignored_while_session_creation_is_submitting() {
        val created = entrySession("created", title = "Created")
        val gateway = BlockingCreateGateway(entrySession("initial", title = "Initial"), created)
        val holder = blockingCreationHolder(gateway)
        verify(holder)
        awaitState(holder, "the initial Session list") {
            it.sessionList?.sessions?.map { session -> session.id.value } == listOf("initial")
        }

        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("Created"))
        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)
        assertTrue(gateway.createStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertTrue(requireNotNull(holder.uiState.value.sessionList).createSession?.isSubmitting == true)

        holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("needle"))

        val submitting = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("", submitting.searchQuery)
        assertTrue(submitting.createSession?.isSubmitting == true)
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)

        gateway.completeCreate()
        awaitState(holder, "the created Session") {
            it.sessionList?.openedSession?.session?.id == created.id
        }
        holder.close()
    }

    @Test
    fun a_local_query_change_does_not_discard_an_in_flight_refresh_result() {
        val gateway = SlowSessionGateway(SessionPage(listOf(entrySession("initial")), null))
        val holder = slowHolder(gateway)
        verifySlow(holder, gateway)

        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        gateway.awaitRequest(SessionListRequest(), occurrence = 1)
        gateway.beforeReturn(SessionListRequest(), occurrence = 1) {
            holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("final"))
        }
        gateway.complete(SessionListRequest(), SessionPage(listOf(entrySession("old-refresh")), null), occurrence = 1)

        awaitState(holder, "the refresh result after the local query change") {
            val list = it.sessionList
            list?.searchQuery == "final" &&
                list.sessions.map { session -> session.id.value } == listOf("old-refresh")
        }
        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals("final", state.searchQuery)
        assertEquals(listOf("old-refresh"), state.sessions.map { it.id.value })
        assertTrue(state.visibleSessions.isEmpty())
        holder.close()
    }

    @Test
    fun stale_load_more_results_are_ignored_after_refresh_resets_the_first_page() {
        val gateway = SlowSessionGateway(SessionPage(listOf(entrySession("initial")), nextOffset = 1))
        val holder = slowHolder(gateway)
        verifySlow(holder, gateway)

        val oldRequest = SessionListRequest(offset = 1)
        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)
        gateway.awaitRequest(oldRequest)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        gateway.awaitRequest(SessionListRequest(), occurrence = 1)
        gateway.complete(
            SessionListRequest(),
            SessionPage(listOf(entrySession("refreshed")), nextOffset = 2),
            occurrence = 1,
        )
        awaitSessions(holder, "refreshed")

        gateway.complete(oldRequest, SessionPage(listOf(entrySession("stale-more")), null))

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("refreshed"), state.sessions.map { it.id.value })
        assertEquals(2, state.nextOffset)
        holder.close()
    }

    @Test
    fun selecting_a_session_opens_authoritative_history_and_a_deleted_session_is_recoverable() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Listed title")), null))
        gateway.openedSession = entrySession(sessionId.value, "Authoritative title")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Authoritative content")),
            )
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
        assertEquals("Authoritative title", opened.session.title)
        assertEquals(listOf("Authoritative content"), opened.messages.map { it.content })
        assertEquals(listOf("open", "history"), gateway.operations)

        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        val unavailable = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, unavailable.errorCategory)
        assertTrue(unavailable.isUnavailable)
        assertEquals(listOf("session-one"), unavailable.sessions.map { it.id.value })
        holder.close()
    }
}
