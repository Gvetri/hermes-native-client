package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunReconciliationStateHolderTest {
    @Test
    fun terminal_observation_replaces_temporary_response_with_authoritative_history() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("user-1", "user", "Run this"),
                    GatewayHistoryMessage("assistant-1", "assistant", "Confirmed result", run.id, "succeeded"),
                ),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observation =
                    ScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Temporary", "delta"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                        ),
                    )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf("user-1", "assistant-1"), opened.messages.map { it.id })
            assertEquals("Confirmed result", opened.messages.last().content)
            assertEquals(null, opened.activeResponse)
            assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
            assertFalse(opened.isRefreshing)
            assertEquals(listOf(run.id), gateway.statusRequests)
            assertEquals(2, gateway.historyRequests)
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_refetches_and_remains_uncertain_without_resubmitting() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                statuses.add(run.copy(status = "running"))
                runs.add(run)
                observation =
                    ThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta"),
                        ),
                    )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(RunPresentationState.UNCERTAIN, opened.latestRunState)
            assertEquals("Partial", opened.activeResponse?.content)
            assertTrue(requireNotNull(opened.activeResponse).streamInterrupted)
            assertFalse(opened.isRefreshing)
            assertEquals(listOf(run.id), gateway.statusRequests)
            assertEquals(2, gateway.historyRequests)

            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun refreshing_an_observed_run_keeps_the_single_observer_open() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                runs.add(run)
                statuses.add(run.copy(status = "running"))
                this.observation = observation
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) { it.sessionList?.openedSession?.isRefreshing == false }

            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertFalse(observation.closed.await(100, TimeUnit.MILLISECONDS))
            assertTrue(gateway.statusRequests.contains(run.id))
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun send_timeout_refetches_session_history_and_does_not_submit_again() {
        val session = session()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            awaitState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }
            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals("Keep this draft", opened.composerText)
            assertFalse(opened.isSending)
            assertEquals(1, gateway.runRequests.size)
            assertEquals(2, gateway.historyRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    private fun holder(
        gateway: FakeGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        sendTimeoutMillis: Long = 30_000L,
    ): EntryStateHolder {
        val repository: GatewayConnectionRepository =
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        return EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
            sendTimeoutMillis = sendTimeoutMillis,
        )
    }

    private fun open(
        holder: EntryStateHolder,
        gateway: FakeGateway,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
        holder.onEvent(EntryUiEvent.SessionClicked(gateway.session.id))
        awaitState(holder) { it.sessionList?.openedSession?.session?.id == gateway.session.id }
    }

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

    private fun session(): Session =
        Session(
            id = SessionId("session-1"),
            title = "Session",
            preview = "Preview",
            pinned = false,
            updatedAt = null,
        )

    private class FakeGateway(
        val session: Session,
    ) : SessionGatewayPort, RunGatewayPort {
        val histories = ArrayDeque<SessionHistory>()
        val statuses = ArrayDeque<Run>()
        val runs = ArrayDeque<Run>()
        val runRequests = mutableListOf<Pair<SessionId, String>>()
        val statusRequests = mutableListOf<RunId>()
        val observedRunIds = mutableListOf<RunId>()
        var historyRequests = 0
        var observation: RunEventObservation = ScriptedObservation(emptyList())
        var blockRunCreation = false
        val runStarted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            historyRequests += 1
            return if (histories.isEmpty()) {
                SessionHistory(sessionId, emptyList(), null)
            } else {
                histories.removeFirst()
            }
        }

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId) = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run {
            runRequests += sessionId to input
            if (blockRunCreation) {
                runStarted.countDown()
                try {
                    check(releaseRun.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "Run was not released." }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } finally {
                    runFinished.countDown()
                }
            }
            return if (runs.isEmpty()) error("missing run") else runs.removeFirst()
        }

        override fun getRunStatus(runId: RunId): Run {
            statusRequests += runId
            return if (statuses.isEmpty()) error("missing status") else statuses.removeFirst()
        }

        override fun observeRun(runId: RunId): RunEventObservation {
            observedRunIds += runId
            return observation
        }
    }

    private open class ScriptedObservation(
        private val events: List<RunEvent>,
    ) : RunEventObservation {
        override fun iterator(): Iterator<RunEvent> = events.iterator()

        override fun close() = Unit
    }

    private class ThrowingObservation(
        events: List<RunEvent>,
    ) : ScriptedObservation(events) {
        override fun iterator(): Iterator<RunEvent> {
            val delegate = super.iterator()
            return object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    return if (delegate.hasNext()) {
                        true
                    } else {
                        throw IllegalStateException("stream interrupted")
                    }
                }

                override fun next(): RunEvent = delegate.next()
            }
        }
    }

    private class BlockingObservation : RunEventObservation {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val closeCount = AtomicInteger(0)

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
            if (closeCount.incrementAndGet() == 1) closed.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
