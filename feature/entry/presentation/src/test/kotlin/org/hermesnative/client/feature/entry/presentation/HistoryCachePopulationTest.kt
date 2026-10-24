package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.NoOpHistoryCacheStore
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HistoryCachePopulationTest {
    private val endpoint = "https://gateway.example/profile"

    @Test
    fun the_history_cache_dependency_defaults_to_the_inert_store() {
        assertEquals(NoOpHistoryCacheStore, EntryStateHolderDependencies().historyCacheStore)
    }

    @Test
    fun a_successful_first_page_load_replaces_the_cached_session_list() {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-one", "First")), null))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")

        assertEquals(listOf(entrySession("session-one", "First")), cache.cachedSessionList(endpoint))
        holder.close()
    }

    @Test
    fun a_successful_next_page_load_merges_the_new_page_into_the_cached_list() {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-one", "First")), 2))
        gateway.enqueueList(SessionPage(listOf(entrySession("session-two", "Second")), null))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)
        awaitSessions(holder, "session-one", "session-two")

        assertEquals(
            listOf(entrySession("session-one", "First"), entrySession("session-two", "Second")),
            cache.cachedSessionList(endpoint),
        )
        holder.close()
    }

    @Test
    fun a_failed_session_list_read_never_writes_to_the_cache() {
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitState(holder, "failed list read") { it.sessionList?.isStale == true }

        assertTrue(cache.writes().isEmpty())
        holder.close()
    }

    @Test
    fun opening_a_session_caches_only_its_fully_successful_transcript() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-one", "user", "Cached content")))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        assertEquals(
            listOf(GatewayHistoryMessage("message-one", "user", "Cached content")),
            cache.cachedTranscript(endpoint, sessionId.value),
        )
        holder.close()
    }

    @Test
    fun a_partially_successful_open_never_writes_to_the_cache() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession("session-other", "Mismatched identity")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-one", "user", "Cached content")))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "failed open") { it.sessionList?.errorCategory != null }

        assertTrue(cache.writes().none { it.startsWith(TRANSCRIPT_WRITE_PREFIX) })
        holder.close()
    }

    @Test
    fun a_failed_open_never_writes_to_the_cache() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "failed open") { it.sessionList?.errorCategory != null }

        assertTrue(cache.writes().none { it.startsWith(TRANSCRIPT_WRITE_PREFIX) })
        holder.close()
    }

    @Test
    fun a_successful_refresh_replaces_the_cached_transcript_wholly() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-one", "user", "Stale content")))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-two", "user", "Fresh content")))
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        awaitState(holder, "refreshed transcript") {
            it.sessionList?.openedSession?.messages?.singleOrNull()?.id == "message-two"
        }

        assertEquals(
            listOf(GatewayHistoryMessage("message-two", "user", "Fresh content")),
            cache.cachedTranscript(endpoint, sessionId.value),
        )
        holder.close()
    }

    @Test
    fun a_successful_refresh_replaces_the_cache_even_after_the_opened_session_leaves_the_state() {
        val gateway = BlockingSessionGateway(blockHistoryRefresh = true)
        val cache = RecordingHistoryCacheStore()
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ -> clientManifestCapabilities() },
                gateway = gateway,
                dispatcher = Dispatchers.Default,
                dependencies = EntryStateHolderDependencies(historyCacheStore = cache),
            )

        try {
            verify(holder)
            awaitSessions(holder, gateway.sessionId.value)
            holder.onEvent(EntryUiEvent.SessionClicked(gateway.sessionId))
            awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

            assertEquals(
                "message-1",
                cache.cachedTranscript(endpoint, gateway.sessionId.value).single().id,
            )

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.refreshStarted.await(ASYNC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder, "back to list") { it.sessionList?.openedSession == null }
            gateway.releaseRefresh.countDown()

            awaitCachedTranscriptMessage(cache, endpoint, gateway.sessionId.value, "message-2")
        } finally {
            holder.close()
        }
    }

    @Test
    fun listing_sessions_never_caches_a_transcript_of_an_unopened_session() {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-one", "First")), 2))
        gateway.enqueueList(SessionPage(listOf(entrySession("session-two", "Second")), null))
        val cache = RecordingHistoryCacheStore()
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "session-one")
        holder.onEvent(EntryUiEvent.LoadMoreSessionsClicked)
        awaitSessions(holder, "session-one", "session-two")
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
        awaitState(holder, "failed list read") { it.sessionList?.isStale == true }

        assertTrue(cache.writes().none { it.startsWith(TRANSCRIPT_WRITE_PREFIX) })
        holder.close()
    }

    private fun connectedCacheHolder(
        gateway: SessionGatewayPort,
        cache: RecordingHistoryCacheStore,
    ): EntryStateHolder =
        holder(
            repository = FakeGatewayConnectionRepository(),
            verifier = { _, _ -> clientManifestCapabilities() },
            gateway = gateway,
            dependencies = EntryStateHolderDependencies(historyCacheStore = cache),
        )

    private companion object {
        const val TRANSCRIPT_WRITE_PREFIX = "write:"
    }
}
