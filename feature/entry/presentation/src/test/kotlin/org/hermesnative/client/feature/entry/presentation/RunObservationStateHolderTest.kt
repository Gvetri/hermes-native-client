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

class RunObservationStateHolderTest {
    @Test
    fun one_foreground_observer_appends_deltas_and_reaches_succeeded_without_duplicate_content() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("assistant-1", "assistant", "Hello world", run.id, "succeeded")),
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    ScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.RUNNING, run.id, "running", eventId = "running"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Hello", "delta-1"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Hello", "delta-1"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", " world", "delta-2"),
                            RunEvent(RunEventType.COMPLETING, run.id, "completing", eventId = "completing"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                        ),
                    )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
            assertEquals(listOf("Hello world"), opened.messages.map { it.content })
            assertTrue(opened.activeResponse == null)
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_preserves_partial_text_and_exposes_uncertain_state() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                observation =
                    ThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(RunPresentationState.UNCERTAIN, opened.latestRunState)
            assertEquals("Partial", opened.activeResponse?.content)
            assertTrue(requireNotNull(opened.activeResponse).streamInterrupted)
            assertFalse(requireNotNull(opened.activeResponse).isStreaming)
            assertTrue(opened.activeRuns.any { it.id == run.id })
        } finally {
            holder.close()
        }
    }

    @Test
    fun returning_to_sessions_closes_only_the_screen_observer_and_does_not_cancel_the_remote_run() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = BlockingObservation()
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)

            assertTrue(observation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<SessionId>(), gateway.cancelledRunIds)
            assertTrue(holder.uiState.value.sessionList?.openedSession == null)
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    private fun holder(
        gateway: ScriptedGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
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
        )
    }

    private fun open(
        holder: EntryStateHolder,
        gateway: ScriptedGateway,
        sessionId: SessionId,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("[REDACTED]"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
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

    private fun session(id: String): Session =
        Session(
            id = SessionId(id),
            title = "Session $id",
            preview = "Preview",
            pinned = false,
            updatedAt = null,
        )

    private class ScriptedGateway(
        val session: Session,
    ) : SessionGatewayPort, RunGatewayPort {
        private val runResults = ArrayDeque<Run>()
        private val historyResults = ArrayDeque<SessionHistory>()
        private val statusResults = ArrayDeque<Run>()
        var observation: RunEventObservation = ScriptedObservation(emptyList())
        val observedRunIds = mutableListOf<RunId>()
        val cancelledRunIds = mutableListOf<RunId>()

        fun enqueueRun(run: Run) {
            runResults += run
        }

        fun enqueueHistory(history: SessionHistory) {
            historyResults += history
        }

        fun enqueueStatus(run: Run) {
            statusResults += run
        }

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            if (historyResults.isEmpty()) {
                SessionHistory(sessionId, emptyList(), null)
            } else {
                historyResults.removeFirst()
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
        ): Run = runResults.removeFirst()

        override fun getRunStatus(runId: RunId): Run =
            if (statusResults.isEmpty()) {
                error("not used")
            } else {
                statusResults.removeFirst()
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
                override fun hasNext(): Boolean =
                    if (delegate.hasNext()) {
                        true
                    } else {
                        throw IllegalStateException("stream interrupted")
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
            if (closeCount.incrementAndGet() == 1) {
                closed.countDown()
            }
            release.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
