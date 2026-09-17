package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntryWiringRestartIntegrationTest {
    @Test
    fun production_wiring_recovers_persisted_run_for_the_same_endpoint() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        val otherEndpointStorage =
            SharedPreferencesRunRecoveryStorage(context) { "https://other-gateway.example/profile" }
        storage.clearForTest()
        otherEndpointStorage.clearForTest()

        val firstHolder = createHolder(context, gateway)
        try {
            connect(firstHolder, " $endpoint ", hasSavedEndpoint = false)
            openSession(firstHolder, gateway.session.id)
            firstHolder.onEvent(EntryUiEvent.ComposerTextChanged("Persist this run"))
            firstHolder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(firstHolder) { it.sessionList?.openedSession?.latestRun?.id == gateway.activeRun.id }
            assertTrue(gateway.observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(1, storage.load().size)
            assertTrue(otherEndpointStorage.load().isEmpty())
        } finally {
            firstHolder.close()
        }

        gateway.terminal = true
        val restartedHolder = createHolder(context, gateway)
        try {
            connect(restartedHolder, endpoint, hasSavedEndpoint = true)
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    while (!gateway.statusRequests.contains(gateway.activeRun.id) || storage.load().isNotEmpty()) {
                        kotlinx.coroutines.delay(25)
                    }
                }
            }
            assertTrue(storage.load().isEmpty())
        } finally {
            restartedHolder.close()
            storage.clearForTest()
            otherEndpointStorage.clearForTest()
        }
    }

    private fun createHolder(
        context: Context,
        gateway: RestartGateway,
    ): EntryStateHolder =
        EntryWiring.createEntryStateHolder(
            context = context,
            capabilityDiscovery = { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
            },
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
        )

    private fun connect(
        holder: EntryStateHolder,
        endpoint: String,
        hasSavedEndpoint: Boolean,
    ) {
        if (!hasSavedEndpoint) {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged(endpoint))
        }
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) { it.sessionList?.sessions?.singleOrNull()?.id == RestartGateway.SESSION_ID }
    }

    private fun openSession(
        holder: EntryStateHolder,
        sessionId: SessionId,
    ) {
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
    }

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (org.hermesnative.client.feature.entry.presentation.EntryUiState) -> Boolean,
    ) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        }
    }

    private fun SharedPreferencesRunRecoveryStorage.clearForTest() {
        save(emptySet())
    }

    private class RestartGateway : SessionGatewayPort, RunGatewayPort {
        companion object {
            val SESSION_ID = SessionId("wiring-session")
        }

        val session = Session(SESSION_ID, "Wiring session", null, pinned = false, updatedAt = null)
        val activeRun = Run(RunId("wiring-run"), SESSION_ID, "running")
        val statusRequests = CopyOnWriteArrayList<RunId>()
        val observation = BlockingObservation()
        var terminal = false

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            if (terminal) {
                SessionHistory(
                    sessionId,
                    listOf(
                        GatewayHistoryMessage(
                            id = "wiring-result",
                            role = "assistant",
                            content = "Recovered result",
                            runId = activeRun.id,
                            runStatus = "succeeded",
                            runResult = "Recovered result",
                        ),
                    ),
                    null,
                )
            } else {
                SessionHistory(sessionId, emptyList(), null)
            }

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId): Unit = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run = activeRun

        override fun getRunStatus(runId: RunId): Run {
            statusRequests += runId
            return if (terminal) activeRun.copy(status = "succeeded") else activeRun
        }

        override fun observeRun(runId: RunId): RunEventObservation = observation
    }

    private class BlockingObservation : RunEventObservation {
        val started = CountDownLatch(1)
        private val release = CountDownLatch(1)

        override fun iterator(): Iterator<RunEvent> =
            object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    started.countDown()
                    release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    return false
                }

                override fun next(): RunEvent = error("not used")
            }

        override fun close() {
            release.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
