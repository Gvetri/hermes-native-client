package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheOfflineReadTest {
    private val endpoint = "https://gateway.example/profile"

    @Test
    fun an_offline_session_list_read_presents_the_cached_session_list() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-one", "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitState(holder, "cached list served") { it.sessionList?.sessions?.isNotEmpty() == true }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("session-one"), state.sessions.map { it.id.value })
        assertEquals("Cached title", state.sessions.single().title)
        assertTrue(state.isStale)
        assertEquals(SessionListErrorCategory.GATEWAY_UNAVAILABLE, state.errorCategory)
        assertTrue(cache.reads().contains("readList"))
        holder.close()
    }

    @Test
    fun an_online_list_success_replaces_presented_cache_and_reads_nothing() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("stale-session", "Stale")))
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("live-session", "Live")), null))
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, "live-session")

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("live-session"), state.sessions.map { it.id.value })
        assertEquals(listOf(entrySession("live-session", "Live")), cache.cachedSessionList(endpoint))
        assertTrue(cache.reads().isEmpty())
        holder.close()
    }

    @Test
    fun an_offline_open_presents_the_cached_transcript() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession(sessionId.value, "Cached title")))
        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-cached", "user", "Cached transcript")),
        )
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Cached title")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "cached transcript served") { it.sessionList?.openedSession != null }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertEquals(listOf("message-cached"), opened.messages.map { it.id })
        assertEquals("Cached transcript", opened.messages.single().content)
        assertTrue(opened.isStale)
        assertTrue(cache.reads().contains("read:${sessionId.value}"))
        holder.close()
    }

    @Test
    fun an_open_failure_without_cached_content_behaves_exactly_as_before_the_cache() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val holder = connectedCacheHolder(gateway, RecordingHistoryCacheStore())

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "failed open") { it.sessionList?.errorCategory != null }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertNull(state.openedSession)
        assertTrue(state.isStale)
        assertTrue(state.isUnavailable)
        assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, state.errorCategory)
        holder.close()
    }

    @Test
    fun an_online_open_never_serves_the_cache() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-stale", "user", "Stale cached")),
        )
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-live", "user", "Live content")))
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertEquals(listOf("message-live"), opened.messages.map { it.id })
        assertEquals(false, opened.isStale)
        assertTrue(cache.reads().isEmpty())
        holder.close()
    }

    @Test
    fun a_refresh_failure_keeps_live_content_and_never_reads_the_cache() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-live", "user", "Live content")))
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-poison", "user", "Poisoned cache")),
        )
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        awaitState(holder, "refresh failure") { it.sessionList?.openedSession?.isStale == true }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertEquals(listOf("message-live"), opened.messages.map { it.id })
        assertTrue(opened.messages.none { it.id == "message-poison" })
        assertEquals(SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED, opened.errorCategory)
        holder.close()
    }

    @Test
    fun a_refresh_failure_with_an_empty_transcript_backfills_from_the_cache() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory = SessionHistory(sessionId, emptyList())
        val holder = connectedCacheHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open empty conversation") { it.sessionList?.openedSession != null }

        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-cached", "user", "Cached transcript")),
        )
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        awaitState(holder, "backfilled transcript") {
            it.sessionList?.openedSession?.messages?.isNotEmpty() == true
        }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertEquals(listOf("message-cached"), opened.messages.map { it.id })
        assertTrue(opened.isStale)
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
}
