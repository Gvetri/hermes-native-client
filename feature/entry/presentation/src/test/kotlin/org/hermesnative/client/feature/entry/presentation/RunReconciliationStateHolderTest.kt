package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RunReconciliationStateHolderTest {
    @Test
    fun history_run_selection_is_atomic_with_unresolved_submission_state() {
        val session = reconciliationSession()
        val latestRun = Run(RunId("latest-run"), session.id, "succeeded")
        val openedSession =
            org.hermesnative.client.feature.entry.application.OpenedSession(
                session,
                SessionHistory(
                    session.id,
                    listOf(GatewayHistoryMessage("result", "assistant", "Done", latestRun.id, "succeeded")),
                ),
            )
        val containsEntered = CountDownLatch(1)
        val releaseContains = CountDownLatch(1)
        val mutationFinished = CountDownLatch(1)
        val unresolvedRuns = mutableMapOf<SessionId, RunId>()
        val blockingRuns =
            ReconciliationBlockingContainsMap(
                delegate = unresolvedRuns,
                entered = containsEntered,
                release = releaseContains,
            )
        val gateway = ReconciliationFakeGateway(session)
        val holder = reconciliationHolder(gateway)
        val lock = privateField(holder, "sessionRequestLock")
        privateField(holder, "uncertainSubmissionRunIds", blockingRuns)
        val selectedRun = AtomicReference<RunId?>()
        val selectionFailure = AtomicReference<Throwable?>()
        val selector = historySelectionProbe(holder, session, openedSession, selectedRun, selectionFailure)
        val mutation = unresolvedRunMutation(lock, unresolvedRuns, session, mutationFinished)

        try {
            selector.start()
            assertTrue(containsEntered.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            mutation.start()
            assertFalse(mutationFinished.await(100, TimeUnit.MILLISECONDS))

            releaseContains.countDown()
            selector.join(RECONCILIATION_TIMEOUT_MILLIS)
            mutation.join(RECONCILIATION_TIMEOUT_MILLIS)

            assertFalse(selector.isAlive)
            assertFalse(mutation.isAlive)
            assertNull(selectionFailure.get())
            assertEquals(latestRun.id, selectedRun.get())
        } finally {
            releaseContains.countDown()
            if (selector.isAlive) selector.join(RECONCILIATION_TIMEOUT_MILLIS)
            if (mutation.isAlive) mutation.join(RECONCILIATION_TIMEOUT_MILLIS)
            holder.close()
        }
    }

    @Test
    fun terminal_observation_replaces_temporary_response_with_authoritative_history() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("user-1", "user", "Run this"),
                    GatewayHistoryMessage("assistant-1", "assistant", "Confirmed result", run.id, "succeeded"),
                ),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observation =
                    ReconciliationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Temporary", "delta"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                        ),
                    )
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
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
    fun confirmed_cancellation_is_terminal_and_does_not_mark_the_run_uncertain() {
        val session = reconciliationSession()
        val run = Run(RunId("run-cancelled"), session.id, "starting")
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("cancelled", "assistant", "Cancelled", run.id, "cancelled")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "cancelled"))
                runs.add(run)
                observation =
                    ReconciliationScriptedObservation(
                        listOf(RunEvent(RunEventType.COMPLETED, run.id, "cancelled", eventId = "cancelled")),
                    )
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.CANCELLED &&
                    it.sessionList?.openedSession?.sendErrorCategory == null
            }
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_sse_event_blocks_send_until_authoritative_reconciliation_finishes() {
        val session = reconciliationSession()
        val run = Run(RunId("run-terminal-race"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(run)
                blockNextStatus = true
                observation =
                    ReconciliationScriptedObservation(
                        listOf(RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "done")),
                    )
            }
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        recoveryRegistry = recoveryRegistry,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == true
            }
            assertEquals(listOf(RunRecoveryEntry(session.id, run.id)), recoveryRegistry.load())

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(listOf(session.id to "First"), gateway.runRequests)

            gateway.releaseStatus.countDown()
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertTrue(recoveryRegistry.load().isEmpty())
        } finally {
            gateway.releaseStatus.countDown()
            holder.close()
        }
    }

    @Test
    fun a_list_pane_refresh_cannot_strand_a_reconciling_conversation() {
        val session = reconciliationSession()
        val run = Run(RunId("run-list-race"), session.id, "succeeded")
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
                    ),
                )
                statuses.add(run)
                runs.add(run)
            }
        val registry = ReconciliationArmingRegistry()
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        recoveryRegistry = registry,
                    ),
            )

        try {
            connectReconciliationGateway(holder, gateway)
            awaitReconciliationCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.armed = true

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            assertTrue(registry.entered.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) { state ->
                val opened = state.sessionList?.openedSession
                opened != null &&
                    opened.session.id == session.id &&
                    opened.isReconciliationInProgress &&
                    !opened.isRefreshing
            }

            val listRequestsBefore = gateway.listRequests
            holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
            val listLoadedOverTheConversation = reconciliationSettles { gateway.listRequests > listRequestsBefore }
            registry.release.countDown()

            assertFalse(
                "the list pane refresh replaced the conversation's in-flight request",
                listLoadedOverTheConversation,
            )
            awaitReconciliationState(holder) { state ->
                val opened = state.sessionList?.openedSession
                opened != null && opened.session.id == session.id && !opened.isReconciliationInProgress
            }
        } finally {
            registry.release.countDown()
            holder.close()
        }
    }

    @Test
    fun replacing_a_reconciling_conversation_leaves_no_reconciliation_outcome_behind() {
        val session = reconciliationSession()
        val run = Run(RunId("run-create-race"), session.id, "succeeded")
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
                    ),
                )
                statuses.add(run)
                runs.add(run)
            }
        val registry = ReconciliationArmingRegistry(failAfterRelease = true)
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        recoveryRegistry = registry,
                    ),
            )

        try {
            connectReconciliationGateway(holder, gateway)
            awaitReconciliationCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.armed = true

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            assertTrue(registry.entered.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) { it.sessionList?.openedSession?.isReconciliationInProgress == true }

            val replacedRequest = privateField(holder, "sessionJob") as? Job
            holder.onEvent(EntryUiEvent.CreateSessionClicked)
            awaitReconciliationState(holder) { it.sessionList?.createSession != null }
            assertNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            registry.release.countDown()

            runBlocking { replacedRequest?.join() }

            assertFalse(
                "the replaced conversation's request recorded its outcome after it left the screen",
                privateField(holder, "recoveryLoadFailed") == true ||
                    session.id in recoveryUnavailableSessions(holder),
            )
        } finally {
            registry.release.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_sse_event_marks_retained_stream_stale_and_refreshing_until_authoritative_history_arrives() {
        val session = reconciliationSession()
        val run = Run(RunId("run-terminal-visible"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(run)
                blockNextStatus = true
                observation =
                    ReconciliationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "done"),
                        ),
                    )
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            val pending = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(pending.isReconciliationInProgress)
            assertTrue(pending.isRefreshing)
            assertTrue(pending.isStale)
            assertEquals("Partial", pending.activeResponse?.content)
            assertTrue(pending.messages.isEmpty())

            gateway.releaseStatus.countDown()
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isReconciliationInProgress == false &&
                    !opened.isRefreshing &&
                    !opened.isStale &&
                    opened.activeResponse == null &&
                    opened.messages.lastOrNull()?.content == "Confirmed"
            }
        } finally {
            gateway.releaseStatus.countDown()
            holder.close()
        }
    }

    @Test
    fun refresh_preserves_streamed_response_until_authoritative_status_confirms_the_run() {
        val session = reconciliationSession()
        val run = Run(RunId("run-preserve-response"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val observation =
            ReconciliationBlockingObservation(
                listOf(
                    RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                    RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                ),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                runs.add(run)
                observations.add(observation)
                histories.add(terminalHistory)
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                blockNextStatus = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }
            assertTrue(observation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == true &&
                    it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }

            gateway.releaseStatus.countDown()
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.activeResponse == null &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
        } finally {
            gateway.releaseStatus.countDown()
            observation.release.countDown()
            holder.close()
        }
    }

    private fun historySelectionProbe(
        holder: EntryStateHolder,
        session: Session,
        openedSession: OpenedSession,
        selectedRun: AtomicReference<RunId?>,
        selectionFailure: AtomicReference<Throwable?>,
    ): Thread =
        Thread {
            try {
                selectedRun.set(invokeHistoryRunIdToReconcile(holder, session.id, openedSession))
            } catch (error: Throwable) {
                selectionFailure.set(error)
            }
        }

    private fun unresolvedRunMutation(
        lock: Any,
        unresolvedRuns: MutableMap<SessionId, RunId>,
        session: Session,
        finished: CountDownLatch,
    ): Thread =
        Thread {
            synchronized(lock) {
                unresolvedRuns[session.id] = RunId("unresolved-run")
                finished.countDown()
            }
        }
}
