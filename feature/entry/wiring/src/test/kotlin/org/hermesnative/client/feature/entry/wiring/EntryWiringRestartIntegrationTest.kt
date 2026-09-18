package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
        SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
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
                assertTrue(SharedPreferencesRunSubmissionUncertaintyStore(context).contains(uncertaintyKey))
                assertTrue(storage.load().isEmpty())
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
            SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        }
    }

    @Test
    fun production_wiring_persists_uncertainty_before_a_create_returns() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val uncertaintyKey = PendingRunSubmissionKey(endpoint, RestartGateway.SESSION_ID)
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        storage.clearForTest()
        SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        try {
            gateway.blockCreate = true
            val firstHolder = createHolder(context, gateway)
            try {
                connect(firstHolder, endpoint, hasSavedEndpoint = false)
                openSession(firstHolder, gateway.session.id)
                firstHolder.onEvent(EntryUiEvent.ComposerTextChanged("Keep one request"))
                firstHolder.onEvent(EntryUiEvent.SendMessageClicked)
                assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertTrue(SharedPreferencesRunSubmissionUncertaintyStore(context).contains(uncertaintyKey))

                val restartedHolder = createHolder(context, gateway)
                try {
                    connect(restartedHolder, endpoint, hasSavedEndpoint = true)
                    openSession(restartedHolder, gateway.session.id)
                    awaitState(restartedHolder) {
                        it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                            !it.sessionList!!.openedSession!!.isSending
                    }
                    restartedHolder.onEvent(EntryUiEvent.SendMessageClicked)
                    assertEquals(1, gateway.runRequests.size)
                } finally {
                    restartedHolder.close()
                }
            } finally {
                firstHolder.close()
            }
            gateway.releaseCreate.countDown()
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitCondition { !SharedPreferencesRunSubmissionUncertaintyStore(context).contains(uncertaintyKey) }
        } finally {
            gateway.releaseCreate.countDown()
            storage.clearForTest()
            SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        }
    }

    @Test
    fun production_wiring_does_not_bind_a_known_recovery_run_to_a_new_uncertain_submission() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        val uncertaintyKey = PendingRunSubmissionKey(endpoint, RestartGateway.SESSION_ID)
        val knownRun = gateway.externalRun
        val submittedRun = gateway.activeRun
        storage.clearForTest()
        storage.save(setOf(RunRecoveryEntry(gateway.session.id, knownRun.id), RunRecoveryEntry(gateway.session.id, submittedRun.id)))
        gateway.statusByRun[knownRun.id] = knownRun
        gateway.statusByRun[submittedRun.id] = submittedRun
        SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        SharedPreferencesRunSubmissionUncertaintyStore(context).add(uncertaintyKey, setOf(knownRun.id))
        SharedPreferencesRunSubmissionUncertaintyStore(context).markSettled(uncertaintyKey)
        try {
            val holder = createHolder(context, gateway)
            try {
                connect(holder, endpoint, hasSavedEndpoint = false)
                openSession(holder, gateway.session.id)
                awaitState(holder) {
                    val opened = it.sessionList?.openedSession
                    opened != null &&
                        opened.hasUnresolvedSubmission &&
                        opened.activeRuns.any { run -> run.id == submittedRun.id } &&
                        !opened.isReconciliationInProgress
                }
                holder.onEvent(EntryUiEvent.SendMessageClicked)
                assertTrue(gateway.runRequests.isEmpty())
                assertTrue(SharedPreferencesRunSubmissionUncertaintyStore(context).contains(uncertaintyKey))
            } finally {
                holder.close()
            }
        } finally {
            storage.clearForTest()
            SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        }
    }

    @Test
    fun production_wiring_settles_a_pending_create_when_the_holder_closes() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val uncertaintyKey = PendingRunSubmissionKey(endpoint, RestartGateway.SESSION_ID)
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        storage.clearForTest()
        SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        try {
            val firstHolder = createHolder(context, gateway)
            try {
                connect(firstHolder, endpoint, hasSavedEndpoint = false)
                openSession(firstHolder, gateway.session.id)
                gateway.blockCreate = true
                firstHolder.onEvent(EntryUiEvent.ComposerTextChanged("Do not lose recovery"))
                firstHolder.onEvent(EntryUiEvent.SendMessageClicked)
                assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

                firstHolder.close()
                assertFalse(SharedPreferencesRunSubmissionUncertaintyStore(context).isSettled(uncertaintyKey))
            } finally {
                gateway.releaseCreate.countDown()
                firstHolder.close()
            }
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitCondition { !SharedPreferencesRunSubmissionUncertaintyStore(context).contains(uncertaintyKey) }

            gateway.terminal = true
            val restartedHolder = createHolder(context, gateway)
            try {
                connect(restartedHolder, endpoint, hasSavedEndpoint = true)
                openSession(restartedHolder, gateway.session.id)
                awaitState(restartedHolder) {
                    val opened = it.sessionList?.openedSession
                    opened != null &&
                        !opened.hasUnresolvedSubmission &&
                        opened.activeRuns.isEmpty() &&
                        !opened.isReconciliationInProgress
                }
                assertEquals(1, gateway.runRequests.size)
            } finally {
                restartedHolder.close()
            }
        } finally {
            gateway.releaseCreate.countDown()
            storage.clearForTest()
            SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        }
    }

    @Test
    fun production_wiring_settles_a_create_canceled_before_its_body_starts() {
        val context = RuntimeEnvironment.getApplication()
        val gateway = RestartGateway()
        val endpoint = "https://gateway.example/profile"
        val uncertaintyKey = PendingRunSubmissionKey(endpoint, RestartGateway.SESSION_ID)
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        val dispatcher = PausingDispatcher()
        storage.clearForTest()
        SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        try {
            val holder =
                createHolder(
                    context = context,
                    gateway = gateway,
                    coroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
                )
            try {
                connect(holder, endpoint, hasSavedEndpoint = false)
                openSession(holder, gateway.session.id)
                dispatcher.paused = true
                holder.onEvent(EntryUiEvent.ComposerTextChanged("Cancel before create"))
                holder.onEvent(EntryUiEvent.SendMessageClicked)

                holder.close()

                assertTrue(dispatcher.queuedCount > 0)
                assertTrue(SharedPreferencesRunSubmissionUncertaintyStore(context).isSettled(uncertaintyKey))
                assertTrue(gateway.runRequests.isEmpty())
            } finally {
                holder.close()
            }
        } finally {
            storage.clearForTest()
            SharedPreferencesRunSubmissionUncertaintyStore(context).remove(uncertaintyKey)
        }
    }

    private fun createHolder(
        context: Context,
        gateway: RestartGateway,
        coroutineScope: CoroutineScope? = null,
    ): EntryStateHolder =
        EntryWiring.createEntryStateHolder(
            context = context,
            capabilityDiscovery = { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
            },
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            coroutineScope = coroutineScope,
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

    private fun awaitCondition(predicate: () -> Boolean) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (!predicate()) delay(10)
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
        val statusByRun = mutableMapOf<RunId, Run>()
        var terminal = false
        var failCreateAfterAcceptance = false
        var acceptedRunVisible = false
        var blockCreate = false
        val runStarted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)

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
            if (blockCreate) {
                runStarted.countDown()
                try {
                    check(releaseCreate.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "Run was not released."
                    }
                } finally {
                    runFinished.countDown()
                }
            }
            return activeRun
        }

        override fun getRunStatus(runId: RunId): Run {
            statusRequests += runId
            return statusByRun[runId] ?: if (terminal) activeRun.copy(status = "succeeded") else activeRun
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

    private class PausingDispatcher : CoroutineDispatcher() {
        @Volatile
        var paused = false

        private val queued = ArrayDeque<Runnable>()

        val queuedCount: Int
            get() = synchronized(queued) { queued.size }

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            if (paused) {
                synchronized(queued) { queued.addLast(block) }
            } else {
                block.run()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
