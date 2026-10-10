package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.asCoroutineDispatcher
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EntryStateHolderSessionActionsTest {
    @Test
    fun starting_creation_from_an_open_conversation_replaces_it_with_the_creation_form() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Listed title")), null))
        gateway.openedSession = entrySession(sessionId.value, "Authoritative title")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Initial content")),
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
        assertNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)

        holder.onEvent(EntryUiEvent.CreateSessionClicked)

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertNotNull(state.createSession)
        assertNull(state.openedSession)
        holder.close()
    }

    @Test
    fun refreshing_the_session_list_reloads_list_data_and_keeps_the_open_conversation() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Listed title")), null))
        gateway.openedSession = entrySession(sessionId.value, "Authoritative title")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Initial content")),
            )
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Reloaded title")), null))
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
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
        awaitState(holder, "reloaded list") {
            it.sessionList?.sessions?.singleOrNull()?.title == "Reloaded title"
        }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(sessionId, state.openedSession?.session?.id)
        assertTrue(requireNotNull(state.openedSession).messages.isNotEmpty())
        holder.close()
    }

    @Test
    fun refreshing_open_history_replaces_only_confirmed_gateway_data_and_preserves_content_on_failure() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Listed title")), null))
        gateway.openedSession = entrySession(sessionId.value, "Authoritative title")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Initial content")),
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
        holder.onEvent(EntryUiEvent.ComposerTextChanged("Draft"))

        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-two", "assistant", "Confirmed content")),
            )
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val refreshed = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
        assertEquals(listOf("Confirmed content"), refreshed.messages.map { it.content })
        assertEquals("Draft", refreshed.composerText)
        assertFalse(refreshed.isRefreshing)
        assertFalse(refreshed.isStale)
        assertNull(refreshed.errorCategory)

        gateway.openedHistory = null
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val failed = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
        assertEquals(listOf("Confirmed content"), failed.messages.map { it.content })
        assertEquals("Draft", failed.composerText)
        assertFalse(failed.isRefreshing)
        assertTrue(failed.isStale)
        assertEquals(SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED, failed.errorCategory)
        holder.close()
    }

    @Test
    fun pin_is_rejected_during_history_refresh_and_history_refresh_can_complete() {
        val gateway = BlockingSessionGateway(blockHistoryRefresh = true)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val holder = slowHolder(gateway, dispatcher)

        try {
            verify(holder)
            awaitState(holder, "initial Session list") {
                it.sessionList?.sessions?.map { session -> session.id.value } == listOf(gateway.sessionId.value)
            }
            holder.onEvent(EntryUiEvent.SessionClicked(gateway.sessionId))
            awaitState(holder, "opened Session") {
                it.sessionList?.openedSession != null
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.refreshStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(holder.uiState.value.sessionList).openedSession?.isRefreshing == true)

            holder.onEvent(EntryUiEvent.PinSessionClicked(gateway.sessionId))
            assertFalse(gateway.pinStarted.await(250, TimeUnit.MILLISECONDS))
            assertEquals(0, gateway.pinCalls)

            gateway.releaseRefresh.countDown()
            awaitState(holder, "history refresh completion") {
                it.sessionList?.openedSession?.isRefreshing == false
            }
        } finally {
            gateway.releaseRefresh.countDown()
            holder.close()
            dispatcher.close()
        }
    }

    @Test
    fun list_refresh_is_rejected_while_a_history_refresh_is_in_flight() {
        val gateway = BlockingSessionGateway(blockHistoryRefresh = true)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val holder = slowHolder(gateway, dispatcher)

        try {
            verify(holder)
            awaitState(holder, "initial Session list") {
                it.sessionList?.sessions?.map { session -> session.id.value } == listOf(gateway.sessionId.value)
            }
            holder.onEvent(EntryUiEvent.SessionClicked(gateway.sessionId))
            awaitState(holder, "opened Session") {
                it.sessionList?.openedSession != null
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.refreshStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(holder.uiState.value.sessionList).openedSession?.isRefreshing == true)

            holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
            assertFalse(gateway.listRefreshStarted.await(250, TimeUnit.MILLISECONDS))

            gateway.releaseRefresh.countDown()
            awaitState(holder, "history refresh completion") {
                it.sessionList?.openedSession?.isRefreshing == false
            }
        } finally {
            gateway.releaseRefresh.countDown()
            holder.close()
            dispatcher.close()
        }
    }

    @Test
    fun load_more_is_rejected_while_a_history_refresh_is_in_flight() {
        val gateway =
            BlockingSessionGateway(
                blockHistoryRefresh = true,
                blockListRefresh = true,
                nextPageOffset = 2,
            )
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val holder = slowHolder(gateway, dispatcher)

        try {
            verify(holder)
            awaitState(holder, "initial Session list") {
                it.sessionList?.nextOffset == 2
            }
            holder.onEvent(EntryUiEvent.SessionClicked(gateway.sessionId))
            awaitState(holder, "opened Session") {
                it.sessionList?.openedSession != null
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.refreshStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(holder.uiState.value.sessionList).openedSession?.isRefreshing == true)

            holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)
            assertFalse(gateway.listRefreshStarted.await(250, TimeUnit.MILLISECONDS))

            gateway.releaseRefresh.countDown()
            awaitState(holder, "history refresh completion") {
                it.sessionList?.openedSession?.isRefreshing == false
            }
        } finally {
            gateway.releaseRefresh.countDown()
            gateway.releaseListRefresh.countDown()
            holder.close()
            dispatcher.close()
        }
    }

    @Test
    fun history_refresh_is_rejected_while_a_list_refresh_is_in_flight() {
        val gateway = BlockingSessionGateway(blockListRefresh = true)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val holder = slowHolder(gateway, dispatcher)

        try {
            verify(holder)
            awaitState(holder, "initial Session list") {
                it.sessionList?.sessions?.map { session -> session.id.value } == listOf(gateway.sessionId.value)
            }
            holder.onEvent(EntryUiEvent.SessionClicked(gateway.sessionId))
            awaitState(holder, "opened Session") {
                it.sessionList?.openedSession != null
            }

            holder.onEvent(EntryUiEvent.PinSessionClicked(gateway.sessionId))
            assertTrue(gateway.pinStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.listRefreshStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(requireNotNull(holder.uiState.value.sessionList).isRefreshing)

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertFalse(gateway.historyRefreshStarted.await(250, TimeUnit.MILLISECONDS))
            gateway.releaseListRefresh.countDown()
            awaitState(holder, "list refresh completion") {
                it.sessionList?.isRefreshing == false
            }
        } finally {
            gateway.releaseListRefresh.countDown()
            holder.close()
            dispatcher.close()
        }
    }

    @Test
    fun successful_reopen_replaces_stale_session_metadata_and_clears_stale_lifecycle_state() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Listed title")), null))
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.openedSession = entrySession(sessionId.value, "Authoritative title", pinned = true)
        gateway.openedHistory = SessionHistory(sessionId, emptyList<GatewayHistoryMessage>())
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
        val stale = requireNotNull(holder.uiState.value.sessionList)
        assertTrue(stale.isStale)
        assertFalse(stale.isUnavailable)

        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        val reopened = requireNotNull(holder.uiState.value.sessionList)
        assertFalse(reopened.isStale)
        assertFalse(reopened.isUnavailable)
        assertEquals("Authoritative title", reopened.sessions.single().title)
        assertTrue(reopened.sessions.single().pinned)
        assertEquals("Authoritative title", reopened.openedSession?.session?.title)
        holder.close()
    }

    @Test
    fun creation_has_no_remote_mutation_until_confirmation_and_opens_the_returned_session_once() {
        val gateway = FakeSessionGateway()
        val created = entrySession("created", title = null)
        gateway.enqueueList(SessionPage(emptyList(), null))
        gateway.enqueueCreate(created)
        gateway.enqueueList(SessionPage(listOf(created), null))
        gateway.openedSession = entrySession("created", title = "Authoritative created")
        gateway.openedHistory =
            SessionHistory(
                sessionId = SessionId("created"),
                messages = listOf(GatewayHistoryMessage("created-message", "assistant", "Created history")),
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
        assertEquals(0, gateway.createCalls)
        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("Created"))
        assertEquals("Created", requireNotNull(holder.uiState.value.sessionList).createSession?.titleDraft)
        assertEquals(0, gateway.createCalls)

        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(1, gateway.createCalls)
        assertEquals(listOf("Created"), gateway.createTitles)
        assertEquals(listOf("create", "open", "history"), gateway.operations)
        assertEquals("created", state.openedSession?.session?.id?.value)
        assertEquals("Authoritative created", state.openedSession?.session?.title)
        assertEquals(listOf("Created history"), state.openedSession?.messages?.map { it.content })
        assertNull(state.createSession)
        holder.close()
    }

    @Test
    fun cancelling_or_navigating_away_discards_the_in_memory_creation_draft() {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(emptyList(), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("Discarded"))
        holder.onEvent(EntryUiEvent.CancelCreateSessionClicked)
        assertNull(requireNotNull(holder.uiState.value.sessionList).createSession)
        assertEquals(0, gateway.createCalls)

        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("Navigated away"))
        holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
        assertNull(requireNotNull(holder.uiState.value.sessionList).createSession)
        assertEquals(0, gateway.createCalls)
        holder.close()
    }

    @Test
    fun confirmed_creation_failure_preserves_the_draft_and_explicit_retry_can_succeed() {
        val gateway = FakeSessionGateway()
        val created = entrySession("created", title = "Retry title")
        gateway.enqueueList(SessionPage(emptyList(), null))
        gateway.enqueueCreateFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueCreate(created)
        gateway.enqueueList(SessionPage(listOf(created), null))
        gateway.openedSession = created
        gateway.openedHistory = SessionHistory(SessionId("created"), emptyList())
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("Retry title"))
        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)

        val failed = requireNotNull(holder.uiState.value.sessionList).createSession
        assertEquals("Retry title", failed?.titleDraft)
        assertEquals(SessionCreationErrorCategory.GATEWAY_REQUEST_FAILED, failed?.errorCategory)
        assertEquals(1, gateway.createCalls)

        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)

        assertEquals(2, gateway.createCalls)
        assertEquals("created", requireNotNull(holder.uiState.value.sessionList).openedSession?.session?.id?.value)
        holder.close()
    }

    @Test
    fun refresh_failure_after_creation_opens_the_returned_session_without_duplicate_creation() {
        val gateway = FakeSessionGateway()
        val created = entrySession("created", title = "Created")
        gateway.enqueueList(SessionPage(emptyList(), null))
        gateway.enqueueCreate(created)
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueList(SessionPage(listOf(created), null))
        gateway.openedSession = created
        gateway.openedHistory = SessionHistory(SessionId("created"), emptyList())
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)

        val stale = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(1, gateway.createCalls)
        assertEquals("created", stale.openedSession?.session?.id?.value)
        assertTrue(stale.isStale)
        assertTrue(stale.isUnavailable)
        assertNull(stale.createSession)
        assertEquals(listOf("created"), stale.sessions.map { it.id.value })

        holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val recovered = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(1, gateway.createCalls)
        assertFalse(recovered.isStale)
        assertFalse(recovered.isUnavailable)
        assertEquals(listOf("created"), recovered.sessions.map { it.id.value })
        holder.close()
    }

    @Test
    fun blank_creation_title_is_sent_as_null_and_uses_the_untitled_session_fallback() {
        val gateway = FakeSessionGateway()
        val created = entrySession("created", title = null)
        gateway.enqueueList(SessionPage(emptyList(), null))
        gateway.enqueueCreate(created)
        gateway.enqueueList(SessionPage(listOf(created), null))
        gateway.openedSession = created
        gateway.openedHistory = SessionHistory(SessionId("created"), emptyList())
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    clientManifestCapabilities()
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.CreateSessionClicked)
        holder.onEvent(EntryUiEvent.CreateSessionTitleChanged("   "))
        holder.onEvent(EntryUiEvent.ConfirmCreateSessionClicked)

        assertEquals(listOf(null), gateway.createTitles)
        assertEquals("Untitled Session", requireNotNull(holder.uiState.value.sessionList).openedSession?.session?.title)
        holder.close()
    }
}
