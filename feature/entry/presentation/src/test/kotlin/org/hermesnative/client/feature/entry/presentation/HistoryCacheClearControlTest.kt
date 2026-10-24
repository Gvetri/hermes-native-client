package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.SessionDraftKey
import org.hermesnative.client.feature.entry.domain.SessionDraftStore
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCacheClearControlTest {
    private val endpoint = "https://gateway.example/profile"

    @Test
    fun confirming_the_clear_drops_cache_served_rows_and_shows_the_truthful_cleared_state() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-cached", "Cached title")))
        val gateway = FakeSessionGateway()
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ -> clientManifestCapabilities() },
                gateway = gateway as SessionGatewayPort,
                dependencies = EntryStateHolderDependencies(historyCacheStore = cache),
            )
        verify(holder)
        awaitState(holder, "cached list served") { it.sessionList?.isCached == true }
        holder.onEvent(EntryUiEvent.OpenSettingsClicked)

        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertTrue(cache.operations.any { it == "clear" })
        assertTrue(state.sessions.isEmpty())
        assertFalse(state.isCached)
        assertTrue(state.isStale)
        assertTrue(state.isUnavailable)

        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
        awaitState(holder, "offline retry after clear") { it.sessionList?.isRefreshing == false }
        val retried = requireNotNull(holder.uiState.value.sessionList)
        assertTrue(retried.sessions.isEmpty())
        assertFalse(retried.isCached)
        holder.close()
    }

    @Test
    fun confirming_the_clear_preserves_authoritative_rows_and_leaves_no_cache_claim() {
        val cache = RecordingHistoryCacheStore()
        val holder = settingsOpenHolder(cache)

        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        val state = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("session-live"), state.sessions.map { it.id.value })
        assertFalse(state.isCached)
        assertFalse(state.isStale)
        holder.close()
    }

    @Test
    fun confirming_the_clear_closes_a_cache_reopened_transcript_and_keeps_an_authoritative_one() {
        val sessionId = SessionId("session-closed")
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession(sessionId.value, "Cached title")))
        cache.write(
            HistoryCacheKey(endpoint, sessionId),
            listOf(GatewayHistoryMessage("message-cached", "user", "Cached transcript")),
        )
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Cached title")), null))
        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        val holder = clearingCacheHolder(gateway, cache)
        verify(holder)
        awaitSessions(holder, sessionId.value)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder, "cached transcript served") { it.sessionList?.openedSession != null }
        assertTrue(requireNotNull(holder.uiState.value.sessionList?.openedSession).isCached)

        holder.onEvent(EntryUiEvent.OpenSettingsClicked)
        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        assertNull(holder.uiState.value.sessionList?.openedSession)
        holder.close()

        val authoritativeCache = RecordingHistoryCacheStore()
        val liveGateway = FakeSessionGateway()
        liveGateway.enqueueList(SessionPage(listOf(entrySession(sessionId.value, "Live")), null))
        liveGateway.openedSession = entrySession(sessionId.value, "Live")
        liveGateway.openedHistory =
            SessionHistory(
                sessionId,
                listOf(GatewayHistoryMessage("message-live", "user", "Live content")),
            )
        val liveHolder = clearingCacheHolder(liveGateway, authoritativeCache)
        verify(liveHolder)
        awaitSessions(liveHolder, sessionId.value)
        liveHolder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(liveHolder, "authoritative transcript") {
            it.sessionList?.openedSession?.messages?.isNotEmpty() == true
        }
        assertFalse(requireNotNull(liveHolder.uiState.value.sessionList?.openedSession).isCached)

        liveHolder.onEvent(EntryUiEvent.OpenSettingsClicked)
        liveHolder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        liveHolder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        val opened = requireNotNull(liveHolder.uiState.value.sessionList?.openedSession)
        assertFalse(opened.isCached)
        assertEquals(listOf("message-live"), opened.messages.map { it.id })
        liveHolder.close()
    }

    @Test
    fun clearing_the_history_cache_touches_no_draft_recovery_or_uncertainty_store() {
        val drafts = RecordingDraftStore()
        val uncertainty = RecordingUncertaintyStore()
        val recovery = RecordingRecoveryRegistry()
        val cache = RecordingHistoryCacheStore()
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-live", "Live title")), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ -> clientManifestCapabilities() },
                gateway = gateway as SessionGatewayPort,
                dependencies =
                    EntryStateHolderDependencies(
                        runRecoveryRegistry = recovery,
                        runSubmissionUncertaintyStore = uncertainty,
                        sessionDraftStore = drafts,
                        historyCacheStore = cache,
                    ),
            )
        verify(holder)
        awaitSessions(holder, "session-live")
        val draftKey = SessionDraftKey(endpoint, SessionId("session-live"))
        drafts.write(draftKey, "typed text")
        uncertainty.operations.clear()
        recovery.operations.clear()

        holder.onEvent(EntryUiEvent.OpenSettingsClicked)
        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        assertTrue(cache.operations.any { it == "clear" })
        assertEquals("typed text", drafts.read(draftKey))
        assertTrue(drafts.operations.none { "clear" in it || it == "remove" })
        assertTrue(uncertainty.operations.isEmpty())
        assertTrue(recovery.operations.isEmpty())
        holder.close()
    }

    @Test
    fun clearing_cached_conversations_requires_confirmation_and_cancelling_stores_nothing() {
        val cache = RecordingHistoryCacheStore()
        val holder = settingsOpenHolder(cache)
        cache.write(
            HistoryCacheKey(endpoint, SessionId("session-live")),
            listOf(GatewayHistoryMessage("message-cached", "user", "Cached transcript")),
        )

        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)

        assertTrue(holder.uiState.value.isClearHistoryCacheConfirmationOpen)
        assertFalse(cache.operations.any { it.startsWith("clear") })

        holder.onEvent(EntryUiEvent.CancelClearHistoryCacheClicked)

        assertFalse(holder.uiState.value.isClearHistoryCacheConfirmationOpen)
        assertFalse(cache.operations.any { it.startsWith("clear") })

        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        assertFalse(cache.operations.any { it.startsWith("clear") })
        assertTrue(cache.cachedTranscript(endpoint, "session-live").isNotEmpty())
        holder.close()
    }

    @Test
    fun confirming_the_clear_empties_the_whole_store_once_and_closes_the_dialog() {
        val cache = RecordingHistoryCacheStore()
        cache.writeSessionList(endpoint, listOf(entrySession("session-one", "Cached title")))
        cache.writeSessionList("https://gateway.example/other", listOf(entrySession("session-two", "Other")))
        val holder = settingsOpenHolder(cache)

        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        holder.onEvent(EntryUiEvent.ConfirmClearHistoryCacheClicked)

        assertEquals(listOf("clear"), cache.operations.filter { it.startsWith("clear") })
        assertTrue(cache.cachedSessionList(endpoint).isEmpty())
        assertTrue(cache.cachedSessionList("https://gateway.example/other").isEmpty())
        assertFalse(holder.uiState.value.isClearHistoryCacheConfirmationOpen)
        holder.close()
    }

    @Test
    fun the_clear_control_is_honored_only_from_the_settings_surface() {
        val cache = RecordingHistoryCacheStore()
        val holder = connectedClearHolder(cache)

        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)

        assertFalse(holder.uiState.value.isClearHistoryCacheConfirmationOpen)

        holder.onEvent(EntryUiEvent.OpenSettingsClicked)
        holder.onEvent(EntryUiEvent.ClearHistoryCacheClicked)
        assertTrue(holder.uiState.value.isClearHistoryCacheConfirmationOpen)
        holder.close()
    }

    private fun settingsOpenHolder(cache: RecordingHistoryCacheStore): EntryStateHolder {
        val holder = connectedClearHolder(cache)
        holder.onEvent(EntryUiEvent.OpenSettingsClicked)
        return holder
    }

    private fun clearingCacheHolder(
        gateway: SessionGatewayPort,
        cache: RecordingHistoryCacheStore,
    ): EntryStateHolder =
        holder(
            repository = FakeGatewayConnectionRepository(),
            verifier = { _, _ -> clientManifestCapabilities() },
            gateway = gateway,
            dependencies = EntryStateHolderDependencies(historyCacheStore = cache),
        )

    private fun connectedClearHolder(cache: RecordingHistoryCacheStore): EntryStateHolder {
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(entrySession("session-live", "Live title")), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ -> clientManifestCapabilities() },
                gateway = gateway as SessionGatewayPort,
                dependencies = EntryStateHolderDependencies(historyCacheStore = cache),
            )
        verify(holder)
        awaitSessions(holder, "session-live")
        return holder
    }

    private class RecordingDraftStore : SessionDraftStore {
        val operations = mutableListOf<String>()
        private val drafts = mutableMapOf<SessionDraftKey, String>()

        override fun read(key: SessionDraftKey): String? = drafts[key]

        override fun write(
            key: SessionDraftKey,
            text: String,
        ) {
            operations += "write"
            drafts[key] = text
        }

        override fun remove(key: SessionDraftKey) {
            operations += "remove"
            drafts.remove(key)
        }

        override fun clearEndpoint(endpoint: String) {
            operations += "clearEndpoint"
            drafts.keys.filter { it.endpoint == endpoint }.forEach(drafts::remove)
        }
    }

    private class RecordingUncertaintyStore : RunSubmissionUncertaintyStore {
        val operations = mutableListOf<String>()

        override fun add(
            key: PendingRunSubmissionKey,
            knownRunIds: Set<RunId>,
            attemptId: String?,
        ): Boolean {
            operations += "add"
            return true
        }

        override fun remove(
            key: PendingRunSubmissionKey,
            attemptId: String?,
        ): Boolean {
            operations += "remove"
            return true
        }

        override fun contains(key: PendingRunSubmissionKey): Boolean = false

        override fun clearEndpoint(endpoint: String) {
            operations += "clearEndpoint"
        }
    }

    private class RecordingRecoveryRegistry : RunRecoveryRegistry {
        val operations = mutableListOf<String>()
        private val entries = mutableListOf<RunRecoveryEntry>()

        override fun load(): List<RunRecoveryEntry> = entries.toList()

        override fun save(entry: RunRecoveryEntry) {
            operations += "save"
            entries += entry
        }

        override fun remove(entry: RunRecoveryEntry) {
            operations += "remove"
            entries.remove(entry)
        }
    }
}
