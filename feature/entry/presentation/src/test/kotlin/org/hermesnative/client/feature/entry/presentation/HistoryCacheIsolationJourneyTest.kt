package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.HistoryCacheStore
import org.hermesnative.client.feature.entry.domain.NoOpHistoryCacheStore
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journey-shaped isolation verification for #284: the traffic of the cache-run-isolation
 * and offline-cache-read journeys — connect, open, submit, reconcile after a restart —
 * replayed against presentation state. The authoritative traffic must be byte-identical
 * with a poisoned store behind it, must never mark a surface cached, and must never read
 * the store outside the sanctioned offline arms.
 */
class HistoryCacheIsolationJourneyTest {
    @Test
    fun the_run_isolation_journey_traffic_never_reads_or_marks_the_populated_cache() {
        val cache = RecordingHistoryCacheStore()
        val withoutCache = runIsolationJourney(NoOpHistoryCacheStore)
        poisonCache(cache)
        val withCache = runIsolationJourney(cache)

        assertEquals(withoutCache, withCache)
        assertTrue(cache.reads().isEmpty())
        assertEquals(emptyList<String>(), withCache.cachedMarkedSurfaces())
    }

    @Test
    fun the_reconciled_open_and_list_refresh_journey_never_reads_or_marks_the_store() {
        val gateway = ReconciliationFakeGateway(journeySession())
        gateway.histories += SessionHistory(gateway.session.id, emptyList())
        gateway.histories += SessionHistory(gateway.session.id, emptyList())
        val registry = InMemoryRunRecoveryRegistry(listOf(RunRecoveryEntry(gateway.session.id, RECOVERY_RUN_ID)))
        val cache = RecordingHistoryCacheStore()
        poisonCache(cache)
        val holder = journeyHolder(gateway, cache, registry)
        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder, "settled authoritative list") { state ->
                val list = state.sessionList
                list != null && !list.isLoading && !list.isRefreshing && list.sessions.isNotEmpty()
            }

            val state = requireNotNull(holder.uiState.value.sessionList)
            assertEquals(emptyList<String>(), state.cachedMarkedSurfaces())
            assertTrue(state.sessions.isNotEmpty())
            assertTrue(cache.reads().isEmpty())
        } finally {
            holder.close()
        }
    }

    private fun poisonCache(cache: RecordingHistoryCacheStore) {
        cache.writeSessionList(
            JOURNEY_ENDPOINT,
            listOf(entrySession("ghost-session", "Ghost entry")),
        )
        cache.write(
            HistoryCacheKey(JOURNEY_ENDPOINT, SessionId("ghost-session")),
            listOf(GatewayHistoryMessage("message-ghost", "user", "Ghost transcript")),
        )
    }

    private fun runIsolationJourney(cache: HistoryCacheStore): SessionListUiState {
        val session = journeySession()
        val gateway = FakeGateway(listOf(session))
        gateway.enqueueRun(Run(RUN_ID, session.id, "starting"))
        val registry = InMemoryRunRecoveryRegistry()
        val repository: GatewayConnectionRepository =
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        val holder =
            EntryStateHolder(
                initialState = EntryState(isGatewayConnectionConfigured = false),
                verifyGatewayConnection =
                    VerifyGatewayConnection(repository) { _, _ ->
                        GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
                    },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                sessionGatewayFactory = { _, _ -> gateway },
                runGatewayFactory = { _, _ -> gateway },
                dependencies =
                    EntryStateHolderDependencies(
                        runRecoveryRegistry = registry,
                        persistRunRecoveryEntry = { _, entry -> registry.save(entry) },
                        removeRunRecoveryEntry = { _, entry -> registry.remove(entry) },
                        removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                        historyCacheStore = cache,
                    ),
            )
        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged(JOURNEY_ENDPOINT))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitState(holder, "connected sessions") { it.sessionList?.sessions?.isNotEmpty() == true }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Recover this offline draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            return requireNotNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun journeyHolder(
        gateway: ReconciliationFakeGateway,
        cache: HistoryCacheStore,
        registry: RunRecoveryRegistry?,
    ): EntryStateHolder {
        val repository: GatewayConnectionRepository =
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        return EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
                },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            dependencies =
                EntryStateHolderDependencies(
                    runRecoveryRegistry = registry,
                    persistRunRecoveryEntry = registry?.let { value -> { _, entry -> value.save(entry) } },
                    removeRunRecoveryEntry = registry?.let { value -> { _, entry -> value.remove(entry) } },
                    removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                    historyCacheStore = cache,
                ),
        )
    }

    private fun journeySession(): Session = session("session-1")

    private fun SessionListUiState.cachedMarkedSurfaces(): List<String> =
        buildList {
            if (isCached) add("session-list")
            openedSession?.takeIf { it.isCached }?.let { add("opened-session") }
        }

    private companion object {
        const val JOURNEY_ENDPOINT = "https://gateway.example/journey"
        val RECOVERY_RUN_ID = RunId("run-recovery")
        val RUN_ID = RunId("run-1")
    }
}
