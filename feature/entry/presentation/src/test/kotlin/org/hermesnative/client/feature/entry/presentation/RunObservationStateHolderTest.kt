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
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
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
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RunObservationStateHolderTest {
    @Test
    fun one_foreground_observer_appends_deltas_and_reaches_succeeded_without_duplicate_content() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
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
        val holder = holder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
            assertEquals(listOf("Hello world"), opened.messages.map { it.content })
            assertTrue(opened.activeResponse == null)
            assertTrue(recoveryRegistry.load().isEmpty())
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

    @Test
    fun a_locally_started_nonterminal_run_is_registered_and_survives_screen_switching() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "running")
        val observation = BlockingObservation()
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(
                listOf(RunRecoveryEntry(session.id, run.id)),
                recoveryRegistry.load(),
            )

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)

            assertTrue(observation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(RunRecoveryEntry(session.id, run.id)), recoveryRegistry.load())
            assertTrue(gateway.statusRequests.isEmpty())
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun recovery_persistence_failure_keeps_the_created_run_and_marks_its_outcome_uncertain() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "running")
        val observation = BlockingObservation()
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val holder = holder(gateway, Dispatchers.Default, FailingRunRecoveryRegistry())

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    opened.composerText == "Run this"
            }
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun reopening_reconciles_a_local_run_when_a_newer_history_run_is_latest() {
        val session = session("session-1")
        val localRun = Run(RunId("run-local"), session.id, "running")
        val externalRun = Run(RunId("run-external"), session.id, "succeeded")
        val initialObservation = BlockingObservation()
        val reopenedObservation = BlockingObservation()
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(localRun)
                observation = initialObservation
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(initialObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            assertTrue(initialObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.observation = reopenedObservation
            gateway.enqueueStatus(externalRun)
            gateway.enqueueStatus(localRun)
            gateway.enqueueHistory(
                SessionHistory(
                    session.id,
                    listOf(GatewayHistoryMessage("external-result", "assistant", "Remote result", externalRun.id, "succeeded")),
                    null,
                ),
            )
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                gateway.statusRequests == listOf(externalRun.id, localRun.id) &&
                    opened?.activeRuns?.any { run -> run.id == localRun.id } == true &&
                    opened.latestRun?.id == localRun.id &&
                    opened.messages.map { message -> message.runId } == listOf(externalRun.id) &&
                    opened.activeResponse?.runId == localRun.id &&
                    !opened.isReconciliationInProgress
            }
            assertTrue(reopenedObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            initialObservation.release.countDown()
            reopenedObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun restart_reconciles_only_known_local_nonterminal_runs() {
        val session = session("session-1")
        val run = Run(RunId("run-local"), session.id, "running")
        val recoveryEntry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(recoveryEntry))
        val gateway =
            ScriptedGateway(session).apply {
                enqueueStatus(run)
            }
        val holder = holder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            awaitState(holder) {
                it.sessionList?.sessions == listOf(session.toSessionItemUiState()) &&
                    gateway.statusRequests == listOf(run.id)
            }
            assertEquals(listOf(recoveryEntry), recoveryRegistry.load())
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun restart_removes_a_recovery_entry_only_after_terminal_history_confirms_the_run() {
        val session = session("session-1")
        val run = Run(RunId("run-local"), session.id, "succeeded")
        val recoveryEntry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(recoveryEntry))
        val gateway =
            ScriptedGateway(session).apply {
                enqueueStatus(run)
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Done", run.id, "succeeded")),
                        null,
                    ),
                )
            }
        val holder = holder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            awaitState(holder) {
                it.sessionList?.sessions == listOf(session.toSessionItemUiState()) &&
                    gateway.statusRequests == listOf(run.id) &&
                    recoveryRegistry.load().isEmpty()
            }
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    private fun holder(
        gateway: ScriptedGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        recoveryRegistry: RunRecoveryRegistry? = null,
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
            runRecoveryRegistry = recoveryRegistry,
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
        val observedRunIds = CopyOnWriteArrayList<RunId>()
        val statusRequests = CopyOnWriteArrayList<RunId>()
        val cancelledRunIds = CopyOnWriteArrayList<RunId>()

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

        override fun getRunStatus(runId: RunId): Run {
            statusRequests += runId
            return if (statusResults.isEmpty()) {
                error("not used")
            } else {
                statusResults.removeFirst()
            }
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

    private class FailingRunRecoveryRegistry : RunRecoveryRegistry {
        override fun load(): List<RunRecoveryEntry> = emptyList()

        override fun save(entry: RunRecoveryEntry): Unit = error("recovery storage unavailable")

        override fun remove(entry: RunRecoveryEntry) = Unit
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
