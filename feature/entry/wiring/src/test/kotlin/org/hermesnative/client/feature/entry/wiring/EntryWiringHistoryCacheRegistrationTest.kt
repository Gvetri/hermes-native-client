package org.hermesnative.client.feature.entry.wiring

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.data.BoundedHistoryCacheStore
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCredentialStore
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.HistoryCacheStore
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryUiEvent
import org.hermesnative.client.feature.entry.presentation.EntryUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntryWiringHistoryCacheRegistrationTest {
    private val holders = mutableListOf<EntryStateHolder>()

    @After
    fun closeHolders() {
        holders.forEach(EntryStateHolder::close)
    }

    @Test
    fun the_production_history_cache_factory_builds_the_bounded_encrypted_store() {
        val context = RuntimeEnvironment.getApplication()

        assertTrue(buildHistoryCacheStore(context) is BoundedHistoryCacheStore)
    }

    @Test
    fun registered_cache_wiring_keeps_authoritative_reads_and_refreshes_intact_without_a_keystore() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RegistrationGateway()
        val holder = createRegisteredHolder(context, gateway)
        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged(ENDPOINT))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitState(holder) { state ->
                state.sessionList?.sessions?.singleOrNull()?.id == RegistrationGateway.SESSION_ID
            }
            awaitStableConnectedState(holder)

            holder.onEvent(EntryUiEvent.SessionClicked(RegistrationGateway.SESSION_ID))
            awaitState(holder) { state -> state.sessionList?.openedSession?.messages?.isNotEmpty() == true }
            val opened = requireNotNull(holder.uiState.value.sessionList?.openedSession)
            assertEquals(listOf("message-1"), opened.messages.map { it.id })
            assertFalse(opened.isStale)

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) { state ->
                state.sessionList?.openedSession?.messages?.singleOrNull()?.id == "message-2"
            }
        } finally {
            holder.close()
            holders.remove(holder)
        }

        assertFalse(File(context.noBackupFilesDir, CACHE_FILE_NAME).exists())
    }

    @Test
    fun connection_removal_through_production_wiring_purges_the_endpoint_cache() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RegistrationGateway()
        val cache = WiringRecordingCacheStore()
        val holder =
            createRegisteredHolder(
                context = context,
                gateway = gateway,
                historyCacheStore = cache,
            )
        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged(ENDPOINT))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitState(holder) { state ->
                state.sessionList?.sessions?.singleOrNull()?.id == RegistrationGateway.SESSION_ID
            }
            assertTrue(cache.writtenListEndpoints.isNotEmpty())
            cache.writeSessionList(
                "https://gateway.example/other",
                listOf(Session(SessionId("other-session"), "Other", null, false)),
            )

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.ConfirmRemoveGatewayConnectionClicked)

            assertEquals(setOf(cache.writtenListEndpoints.first()), cache.clearedEndpoints.toSet())
            assertTrue(cache.cachedSessionList(ENDPOINT).isEmpty())
            assertEquals(1, cache.cachedSessionList("https://gateway.example/other").size)
        } finally {
            holder.close()
            holders.remove(holder)
        }
    }

    @Test
    fun production_wiring_registers_the_cache_purge_in_the_connection_removal_lambda() {
        val wiring =
            File(
                System.getProperty("fixture.repositoryRoot"),
                "feature/entry/wiring/src/main/kotlin/org/hermesnative/client/feature/entry/wiring/EntryWiring.kt",
            ).readText()
        val removalLambda = wiring.substringAfter(") { endpoint ->").substringBefore("},")

        assertTrue(
            "the history cache purge must be registered in the removal lambda, found: $removalLambda",
            "historyCacheStore::clearEndpoint" in removalLambda,
        )
    }

    private fun awaitStableConnectedState(holder: EntryStateHolder) {
        repeat(SETTLE_PROBES) {
            val sessionList = holder.uiState.value.sessionList
            if (sessionList?.isStale == true || sessionList?.errorCategory != null) {
                fail("authoritative list read was poisoned by the failing cache store")
            }
            Thread.sleep(SETTLE_INTERVAL_MILLIS)
        }
        val sessionList = requireNotNull(holder.uiState.value.sessionList)
        assertFalse(sessionList.isStale)
        assertEquals(null, sessionList.errorCategory)
    }

    private fun createRegisteredHolder(
        context: android.content.Context,
        gateway: RegistrationGateway,
        historyCacheStore: HistoryCacheStore? = null,
    ): EntryStateHolder =
        EntryWiring.createEntryStateHolder(
            context = context,
            overrides =
                EntryWiringOverrides(
                    capabilityDiscovery = { _, _ ->
                        GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
                    },
                    sessionGatewayFactory = { _, _ -> gateway },
                    runGatewayFactory = { _, _ -> gateway },
                    credentialStore = InMemoryCredentialStore(),
                    historyCacheStore = historyCacheStore,
                ),
        ).also { holders += it }

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (EntryUiState) -> Boolean,
    ) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        }
    }

    private class InMemoryCredentialStore : GatewayCredentialStore {
        private var credential: String? = null

        override fun load(): String? = credential

        override fun save(credential: String) {
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }
    }

    private class WiringRecordingCacheStore : HistoryCacheStore {
        val clearedEndpoints = mutableListOf<String>()
        val writtenListEndpoints = mutableListOf<String>()
        private val lists = mutableMapOf<String, List<Session>>()

        override fun read(key: HistoryCacheKey): List<GatewayHistoryMessage> = emptyList()

        override fun write(
            key: HistoryCacheKey,
            messages: List<GatewayHistoryMessage>,
        ) = Unit

        override fun readSessionList(endpoint: String): List<Session> = lists[endpoint].orEmpty()

        override fun writeSessionList(
            endpoint: String,
            sessions: List<Session>,
        ) {
            writtenListEndpoints += endpoint
            lists[endpoint] = sessions
        }

        override fun clearEndpoint(endpoint: String) {
            clearedEndpoints += endpoint
            lists.remove(endpoint)
        }

        override fun clear() {
            lists.clear()
        }

        fun cachedSessionList(endpoint: String): List<Session> = lists[endpoint].orEmpty()
    }

    private class RegistrationGateway : SessionGatewayPort, RunGatewayPort {
        private val historyCalls = AtomicInteger(0)

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = session

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            SessionHistory(
                sessionId = sessionId,
                messages =
                    listOf(
                        GatewayHistoryMessage(
                            id = "message-${historyCalls.incrementAndGet()}",
                            role = "user",
                            content = "Live content",
                        ),
                    ),
            )

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = session

        override fun deleteSession(sessionId: SessionId): Unit = Unit

        override fun pinSession(sessionId: SessionId): SessionPinResult = SessionPinResult(sessionId, false)

        override fun unpinSession(sessionId: SessionId): SessionPinResult = SessionPinResult(sessionId, false)

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run = Run(RunId("run-1"), sessionId, "completed")

        override fun getRunStatus(runId: RunId): Run = Run(runId, SESSION_ID, "completed")

        override fun observeRun(runId: RunId) = error("not used")

        companion object {
            val SESSION_ID: SessionId = SessionId("registered-session")
            val session: Session = Session(SESSION_ID, "Registered", "Preview", false)
        }
    }

    private companion object {
        const val ENDPOINT = "https://gateway.example/registered"
        const val CACHE_FILE_NAME = "gateway_history_cache.enc"
        const val TEST_TIMEOUT_MILLIS = 10_000L
        const val SETTLE_PROBES = 120
        const val SETTLE_INTERVAL_MILLIS = 25L
    }
}
