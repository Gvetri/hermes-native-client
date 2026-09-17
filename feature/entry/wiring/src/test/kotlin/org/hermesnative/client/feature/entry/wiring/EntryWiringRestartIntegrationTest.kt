package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import kotlinx.coroutines.delay
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
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryUiEvent
import org.hermesnative.client.feature.entry.presentation.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.presentation.ProcessRunSubmissionUncertaintyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        try {
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
                assertFalse(gateway.statusRequests.contains(gateway.externalRun.id))
            } finally {
                restartedHolder.close()
            }
        } finally {
            storage.clearForTest()
            otherEndpointStorage.clearForTest()
        }
    }

    @Test
    fun production_wiring_keeps_a_response_loss_uncertain_after_holder_recreation() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        val uncertaintyKey = PendingRunSubmissionKey(endpoint, RestartGateway.SESSION_ID)
        storage.clearForTest()
        ProcessRunSubmissionUncertaintyStore.remove(uncertaintyKey)
        try {
            val firstHolder = createHolder(context, gateway)
            try {
                connect(firstHolder, endpoint, hasSavedEndpoint = false)
                openSession(firstHolder, gateway.session.id)
                gateway.failCreateAfterAcceptance = true
                firstHolder.onEvent(EntryUiEvent.ComposerTextChanged("Response may be lost"))
                firstHolder.onEvent(EntryUiEvent.SendMessageClicked)
                awaitState(firstHolder) {
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                        it.sessionList?.openedSession?.isSending == false
                }
                assertEquals(1, gateway.runRequests.size)
                awaitRecoveryEntries(storage, setOf(RunRecoveryEntry(gateway.session.id, gateway.activeRun.id)))
            } finally {
                firstHolder.close()
            }

            val restartedHolder = createHolder(context, gateway)
            try {
                connect(restartedHolder, endpoint, hasSavedEndpoint = true)
                openSession(restartedHolder, gateway.session.id)
                awaitState(restartedHolder) {
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                        it.sessionList?.openedSession?.isSending == false
                }
                restartedHolder.onEvent(EntryUiEvent.SendMessageClicked)
                assertEquals(1, gateway.runRequests.size)
            } finally {
                restartedHolder.close()
            }
        } finally {
            storage.clearForTest()
            ProcessRunSubmissionUncertaintyStore.remove(uncertaintyKey)
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

    private fun awaitRecoveryEntries(
        storage: SharedPreferencesRunRecoveryStorage,
        expected: Set<RunRecoveryEntry>,
    ) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (storage.load() != expected) delay(10)
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
        val externalRun = Run(RunId("external-run"), SESSION_ID, "succeeded")
        val statusRequests = CopyOnWriteArrayList<RunId>()
        val runRequests = CopyOnWriteArrayList<Pair<SessionId, String>>()
        val observation = BlockingObservation()
        var terminal = false
        var failCreateAfterAcceptance = false
        var acceptedRunVisible = false

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            if (acceptedRunVisible) {
                SessionHistory(
                    sessionId,
                    listOf(
                        GatewayHistoryMessage(
                            id = "accepted-result",
                            role = "assistant",
                            content = "Accepted result",
                            runId = activeRun.id,
                            runStatus = if (terminal) "succeeded" else activeRun.status,
                            runResult = null,
                        ),
                    ),
                    null,
                )
            } else if (terminal) {
                SessionHistory(
                    sessionId,
                    listOf(
                        GatewayHistoryMessage(
                            id = "external-result",
                            role = "assistant",
                            content = "External result",
                            runId = externalRun.id,
                            runStatus = externalRun.status,
                            runResult = "External result",
                        ),
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
        ): Run {
            runRequests += sessionId to input
            if (failCreateAfterAcceptance) {
                acceptedRunVisible = true
                throw IllegalStateException("response lost after acceptance")
            }
            return activeRun
        }

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
