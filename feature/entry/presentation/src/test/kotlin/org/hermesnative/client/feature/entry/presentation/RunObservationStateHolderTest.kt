package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
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
    fun disconnect_during_create_persists_the_remote_run_in_its_original_endpoint_scope() {
        val session = session("disconnect-during-create")
        val run = Run(RunId("run-disconnected"), session.id, "running")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                blockCreate = true
            }
        val persisted = CopyOnWriteArrayList<Pair<String, RunRecoveryEntry>>()
        val holder =
            holder(
                gateway = gateway,
                dispatcher = Dispatchers.Default,
                persistRunRecoveryEntry = { endpoint, entry -> persisted += endpoint to entry },
            )

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.createStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            gateway.createRelease.countDown()

            awaitCondition { persisted.isNotEmpty() }
            assertEquals(
                listOf("https://gateway.example/profile" to RunRecoveryEntry(session.id, run.id)),
                persisted.toList(),
            )
            assertTrue(gateway.cancelledRunIds.isEmpty())
        } finally {
            gateway.createRelease.countDown()
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
    fun recovery_load_failure_blocks_submission_until_authoritative_recovery_is_available() {
        val gateway = ScriptedGateway(session("recovery-load-failure"))
        val holder = holder(gateway, recoveryRegistry = FailingLoadRunRecoveryRegistry())

        try {
            open(holder, gateway, gateway.session.id)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.hasUnresolvedSubmission == true &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(0, gateway.createRunCount.get())
        } finally {
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
    fun recovery_removal_failure_keeps_metadata_without_failing_the_recovery_job() {
        val session = session("session-remove-failure")
        val run = Run(RunId("run-remove-failure"), session.id, "succeeded")
        val entry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = FailingRemoveRunRecoveryRegistry(entry)
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
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitCondition { gateway.statusRequests.contains(run.id) }
            assertEquals(listOf(entry), recoveryRegistry.load())
        } finally {
            holder.close()
        }
    }

    @Test
    fun recovery_continues_after_switching_sessions_while_authoritative_queries_are_blocked() {
        val firstSession = session("session-recovery-1")
        val secondSession = session("session-recovery-2")
        val firstRun = Run(RunId("run-recovery-1"), firstSession.id, "running")
        val secondRun = Run(RunId("run-recovery-2"), secondSession.id, "running")
        val recoveryRegistry =
            InMemoryRunRecoveryRegistry(
                listOf(
                    RunRecoveryEntry(firstSession.id, firstRun.id),
                    RunRecoveryEntry(secondSession.id, secondRun.id),
                ),
            )
        val gateway =
            ScriptedGateway(firstSession, listOf(secondSession)).apply {
                blockStatus = true
                statusByRun[firstRun.id] = firstRun.copy(status = "succeeded")
                statusByRun[secondRun.id] = secondRun.copy(status = "succeeded")
                historyBySession[firstSession.id] =
                    SessionHistory(
                        firstSession.id,
                        listOf(GatewayHistoryMessage("first-result", "assistant", "Done", firstRun.id, "succeeded")),
                        null,
                    )
                historyBySession[secondSession.id] =
                    SessionHistory(
                        secondSession.id,
                        listOf(GatewayHistoryMessage("second-result", "assistant", "Done", secondRun.id, "succeeded")),
                        null,
                    )
            }
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitState(holder) { it.sessionList?.sessions?.size == 2 }
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(firstSession.id))
            awaitState(holder) { it.sessionList?.openedSession?.session?.id == firstSession.id }
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(secondSession.id))
            awaitState(holder) { it.sessionList?.openedSession?.session?.id == secondSession.id }

            gateway.statusRelease.countDown()
            awaitCondition {
                recoveryRegistry.load().isEmpty() &&
                    gateway.statusRequests.containsAll(listOf(firstRun.id, secondRun.id))
            }
        } finally {
            gateway.statusRelease.countDown()
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
        persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
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
            persistRunRecoveryEntry = persistRunRecoveryEntry,
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

    private fun awaitCondition(predicate: () -> Boolean) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (!predicate()) delay(10)
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
        private val additionalSessions: List<Session> = emptyList(),
    ) : SessionGatewayPort, RunGatewayPort {
        private val runResults = ArrayDeque<Run>()
        private val historyResults = ArrayDeque<SessionHistory>()
        private val statusResults = ArrayDeque<Run>()
        val statusByRun = ConcurrentHashMap<RunId, Run>()
        val historyBySession = ConcurrentHashMap<SessionId, SessionHistory>()
        var observation: RunEventObservation = ScriptedObservation(emptyList())
        val observedRunIds = CopyOnWriteArrayList<RunId>()
        val statusRequests = CopyOnWriteArrayList<RunId>()
        val cancelledRunIds = CopyOnWriteArrayList<RunId>()
        val createRunCount = AtomicInteger(0)
        var blockCreate = false
        val createStarted = CountDownLatch(1)
        val createRelease = CountDownLatch(1)
        var blockStatus = false
        val statusStarted = CountDownLatch(1)
        val statusRelease = CountDownLatch(1)

        fun enqueueRun(run: Run) {
            runResults += run
        }

        fun enqueueHistory(history: SessionHistory) {
            historyResults += history
        }

        fun enqueueStatus(run: Run) {
            statusResults += run
        }

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session) + additionalSessions, null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = (listOf(session) + additionalSessions).single { it.id == sessionId }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            historyBySession[sessionId]
                ?: if (historyResults.isEmpty()) {
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
        ): Run {
            createRunCount.incrementAndGet()
            if (blockCreate) {
                createStarted.countDown()
                createRelease.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            return runResults.removeFirst()
        }

        override fun getRunStatus(runId: RunId): Run {
            statusRequests += runId
            if (blockStatus) {
                statusStarted.countDown()
                statusRelease.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            return statusByRun[runId]
                ?: if (statusResults.isEmpty()) {
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

    private class FailingLoadRunRecoveryRegistry : RunRecoveryRegistry {
        override fun load(): List<RunRecoveryEntry> = error("recovery storage unavailable")

        override fun save(entry: RunRecoveryEntry) = Unit

        override fun remove(entry: RunRecoveryEntry) = Unit
    }

    private class FailingRemoveRunRecoveryRegistry(
        private val entry: RunRecoveryEntry,
    ) : RunRecoveryRegistry {
        override fun load(): List<RunRecoveryEntry> = listOf(entry)

        override fun save(entry: RunRecoveryEntry) = Unit

        override fun remove(entry: RunRecoveryEntry): Unit = error("recovery cleanup unavailable")
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
