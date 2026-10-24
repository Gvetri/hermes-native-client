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
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
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
import java.io.File

class HistoryCacheIsolationTest {
    @Test
    fun run_recovery_and_reconciliation_with_a_populated_cache_behave_exactly_like_a_cacheless_run() {
        val withoutCache = runReconciledOpenScenario(NoOpHistoryCacheStore)
        val cache = RecordingHistoryCacheStore()
        poisonCache(cache)
        val withCache = runReconciledOpenScenario(cache)

        assertEquals(withoutCache, withCache)
        assertTrue(cache.reads().isEmpty())
    }

    @Test
    fun session_refresh_reconciliation_with_a_populated_cache_behaves_exactly_like_a_cacheless_run() {
        val withoutCache = runRefreshReconcileScenario(NoOpHistoryCacheStore)
        val cache = RecordingHistoryCacheStore()
        poisonCache(cache)
        val withCache = runRefreshReconcileScenario(cache)

        assertEquals(withoutCache, withCache)
        assertTrue(cache.reads().isEmpty())
    }

    @Test
    fun a_successful_submission_with_a_populated_cache_behaves_exactly_like_a_cacheless_run() {
        val withoutCache = runSubmissionScenario(NoOpHistoryCacheStore)
        val cache = RecordingHistoryCacheStore()
        poisonCache(cache)
        val withCache = runSubmissionScenario(cache)

        assertEquals(withoutCache, withCache)
        assertTrue(cache.reads().isEmpty())
    }

    @Test
    fun a_failed_submission_with_a_populated_cache_behaves_exactly_like_a_cacheless_run() {
        val withoutCache = runFailedSubmissionScenario(NoOpHistoryCacheStore)
        val cache = RecordingHistoryCacheStore()
        poisonCache(cache)
        val withCache = runFailedSubmissionScenario(cache)

        assertEquals(withoutCache, withCache)
        assertTrue(cache.reads().isEmpty())
    }

    @Test
    fun recovery_reconciliation_and_uncertainty_sources_never_reference_the_history_cache() {
        val root = File(System.getProperty("fixture.repositoryRoot"))
        val isolationSources =
            listOf(
                PRESENTATION_MAIN + "EntryStateHolderRecoveryLoad.kt",
                PRESENTATION_MAIN + "EntryStateHolderRecoveryWork.kt",
                PRESENTATION_MAIN + "EntryStateHolderReconciliation.kt",
                PRESENTATION_MAIN + "EntryStateHolderReconciliationApply.kt",
                PRESENTATION_MAIN + "ProcessRunSubmissionUncertaintyStore.kt",
                APPLICATION_MAIN + "ReconcileSession.kt",
                APPLICATION_MAIN + "ReconcileRun.kt",
            )
        val violations =
            isolationSources.flatMap { path ->
                File(root, path).readLines().mapIndexedNotNull { index, line ->
                    if ("historyCache" in line || "HistoryCache" in line) "$path:${index + 1}" else null
                }
            }

        assertEquals(emptyList<String>(), violations)
    }

    private fun poisonCache(cache: RecordingHistoryCacheStore) {
        cache.writeSessionList(RECONCILED_ENDPOINT, listOf(entrySession("ghost-session", "Ghost entry")))
        cache.write(
            HistoryCacheKey(RECONCILED_ENDPOINT, RECONCILED_SESSION_ID),
            listOf(GatewayHistoryMessage("message-ghost", "user", "Ghost transcript")),
        )
    }

    private fun runReconciledOpenScenario(cache: HistoryCacheStore): SessionListUiState {
        val gateway = ReconciliationFakeGateway(reconciliationSession())
        gateway.histories += SessionHistory(gateway.session.id, emptyList())
        gateway.statuses += Run(RECOVERY_RUN_ID, gateway.session.id, "completed")
        val registry = InMemoryRunRecoveryRegistry(listOf(RunRecoveryEntry(gateway.session.id, RECOVERY_RUN_ID)))
        val holder = reconciledCacheHolder(gateway, cache, registry)
        try {
            openReconciledSession(holder, gateway)
            return requireNotNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun runRefreshReconcileScenario(cache: HistoryCacheStore): SessionListUiState {
        val gateway = ReconciliationFakeGateway(reconciliationSession())
        gateway.histories += SessionHistory(gateway.session.id, emptyList())
        gateway.histories += SessionHistory(gateway.session.id, emptyList())
        val holder = reconciledCacheHolder(gateway, cache, null)
        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession?.isRefreshing == false }
            return requireNotNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun reconciledCacheHolder(
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

    private fun runSubmissionScenario(cache: HistoryCacheStore): SessionListUiState {
        val session = submissionSession()
        val gateway = FakeGateway(listOf(session))
        gateway.enqueueRun(Run(RunId("run-1"), session.id, "starting"))
        val holder = submissionCacheHolder(gateway, cache)
        try {
            openSubmissionSession(holder, session)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            return requireNotNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun runFailedSubmissionScenario(cache: HistoryCacheStore): SessionListUiState {
        val session = submissionSession()
        val gateway = FakeGateway(listOf(session))
        gateway.enqueueRunFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        val holder = submissionCacheHolder(gateway, cache)
        try {
            openSubmissionSession(holder, session)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Reject this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            return requireNotNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun submissionCacheHolder(
        gateway: FakeGateway,
        cache: HistoryCacheStore,
    ): EntryStateHolder {
        val repository = FakeGatewayConnectionRepository()
        return EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    clientManifestCapabilities()
                },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            dependencies =
                EntryStateHolderDependencies(
                    removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                    historyCacheStore = cache,
                ),
        )
    }

    private fun openSubmissionSession(
        holder: EntryStateHolder,
        session: Session,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged(RECONCILED_ENDPOINT))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder, "connected sessions") { it.sessionList?.sessions?.isNotEmpty() == true }
        holder.onEvent(EntryUiEvent.SessionClicked(session.id))
        awaitState(holder, "open conversation") { it.sessionList?.openedSession != null }
    }

    private fun submissionSession(): Session = session("session-1")

    private companion object {
        const val PRESENTATION_MAIN =
            "feature/entry/presentation/src/main/kotlin/org/hermesnative/client/feature/entry/presentation/"
        const val APPLICATION_MAIN =
            "feature/entry/application/src/main/kotlin/org/hermesnative/client/feature/entry/application/"

        const val RECONCILED_ENDPOINT = "https://gateway.example/profile"
        val RECONCILED_SESSION_ID = SessionId("session-1")
        val RECOVERY_RUN_ID = RunId("run-recovery")
    }
}
