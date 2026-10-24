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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheMarkingTest {
    private val endpoint = "https://gateway.example/profile"

    @Test
    fun an_offline_cached_list_is_marked_cached() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-one", "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitState(holder, "cached list served") { it.sessionList?.sessions?.isNotEmpty() == true }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertTrue(state.isCached)
        holder.close()
    }

    @Test
    fun an_authoritative_refresh_after_a_cached_list_unmarks_the_cache() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-cached", "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueList(SessionPage(listOf(entrySession("session-live", "Live title")), null))
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitState(holder, "cached list served") { it.sessionList?.isCached == true }
        holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
        awaitState(holder, "authoritative list") {
            it.sessionList?.sessions?.singleOrNull()?.id?.value == "session-live"
        }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertFalse(state.isCached)
        assertFalse(state.isStale)
        assertEquals(listOf(entrySession("session-live", "Live title")), cache.cachedSessionList(endpoint))
        holder.close()
    }

    @Test
    fun an_offline_reopened_transcript_is_marked_cached() {
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
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "cached transcript served") { it.sessionList?.openedSession != null }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertTrue(opened.isCached)
        assertTrue(opened.isStale)
        holder.close()
    }

    @Test
    fun a_refresh_failure_backfill_from_cache_marks_the_transcript_cached() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory = SessionHistory(sessionId, emptyList())
        val holder = connectedMarkingHolder(gateway, cache)

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
        assertTrue(opened.isCached)
        holder.close()
    }

    @Test
    fun a_refresh_failure_with_preserved_live_content_is_not_marked_cached() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "First")), null))
        gateway.openedSession = entrySession(sessionId.value, "First")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-live", "user", "Live content")))
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }

        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        awaitState(holder, "refresh failure") { it.sessionList?.openedSession?.isStale == true }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertEquals(listOf("message-live"), opened.messages.map { it.id })
        assertFalse(opened.isCached)
        holder.close()
    }

    @Test
    fun an_offline_open_of_a_never_loaded_session_shows_no_cached_transcript_and_no_cache_mark() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession(sessionId.value, "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Cached title")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "failed open") { it.sessionList?.errorCategory != null }

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertNull(state.openedSession)
        assertFalse(state.isCached)
        assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, state.errorCategory)
        holder.close()
    }

    @Test
    fun reconnecting_after_a_cached_transcript_returns_to_authoritative_content_and_replaces_the_cache() {
        val sessionId = SessionId("session-one")
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession(sessionId.value, "Cached title")))
        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-stale", "user", "Stale cached")),
        )
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Cached title")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "cached transcript served") { it.sessionList?.openedSession != null }
        assertTrue(requireNotNull(holder.uiState.value.sessionList?.openedSession).isCached)

        gateway.openFailure = null
        gateway.openedSession = entrySession(sessionId.value, "Cached title")
        gateway.openedHistory =
            SessionHistory(sessionId, listOf(GatewayHistoryMessage("message-live", "user", "Live content")))
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
        awaitState(holder, "authoritative transcript") {
            it.sessionList?.openedSession?.messages?.singleOrNull()?.id == "message-live"
        }

        val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
        assertFalse(opened.isCached)
        assertFalse(opened.isStale)
        assertEquals(
            listOf(GatewayHistoryMessage("message-live", "user", "Live content")),
            cache.cachedTranscript(endpoint, sessionId.value),
        )
        holder.close()
    }

    @Test
    fun rows_served_from_a_cleared_store_keep_their_truthful_cache_mark() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-one", "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val holder = connectedMarkingHolder(gateway, cache)

        verify(holder)
        awaitState(holder, "cached list served") { it.sessionList?.isCached == true }

        cache.clear()
        holder.onEvent(EntryUiEvent.RefreshSessionListClicked)

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("session-one"), state.sessions.map { it.id.value })
        assertTrue(state.isCached)
        assertTrue(state.isStale)
        holder.close()
    }

    private fun connectedMarkingHolder(
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
