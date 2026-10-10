package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunReconciliationObservationTest {
    @Test
    fun timed_out_external_run_does_not_resolve_the_unresolved_submission() {
        val session = reconciliationSession()
        val submittedRun = Run(RunId("run-submitted"), session.id, "succeeded")
        val laterRun = Run(RunId("run-later"), session.id, "failed")
        val history =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("user", "user", "Keep this draft", submittedRun.id, "succeeded"),
                    GatewayHistoryMessage("submitted", "assistant", "Submitted", submittedRun.id, "succeeded"),
                    GatewayHistoryMessage("later", "assistant", "Later failure", laterRun.id, "failed"),
                ),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(submittedRun)
                statuses.add(laterRun)
                blockRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        sendTimeoutMillis = 50L,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isSending == false &&
                    opened.latestRun?.id == laterRun.id &&
                    opened.latestRunState == RunPresentationState.FAILED &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    opened.composerText == "Keep this draft"
            }
            @Suppress("UNCHECKED_CAST")
            val localRuns = (privateField(holder, "sessionRuns") as Map<SessionId, List<Run>>)[session.id].orEmpty()
            assertTrue(localRuns.none { it.id == submittedRun.id || it.id == laterRun.id })
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun active_status_with_stale_terminal_history_preserves_the_streamed_response() {
        val session = reconciliationSession()
        val run = Run(RunId("run-stale-history"), session.id, "running")
        val staleTerminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("stale", "assistant", "Stale result", run.id, "succeeded")),
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
                histories.add(staleTerminalHistory)
                histories.add(staleTerminalHistory)
                statuses.add(run)
                runs.add(run)
                observations.add(observation)
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
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_status_settles_the_reopened_outcome_and_applies_authoritative_history() {
        val session = reconciliationSession()
        val run = Run(RunId("run-stale-terminal-history"), session.id, "starting")
        val staleHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("stale-user", "user", "Run this")),
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
                histories.add(staleHistory)
                histories.add(staleHistory)
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observations.add(observation)
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
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isRefreshing == false &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.messages.map { message -> message.id } == listOf("stale-user") &&
                    opened.activeResponse == null
            }
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_run_returned_by_create_is_reconciled_before_it_is_presented_as_confirmed() {
        val session = reconciliationSession()
        val terminalRun = Run(RunId("run-created-terminal"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Created result", terminalRun.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(terminalRun)
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Create terminal"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.activeResponse == null
            }
            assertEquals(listOf(terminalRun.id), gateway.statusRequests)
            assertEquals(2, gateway.historyRequests)
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun timed_out_send_retains_unrelated_run_visibility_without_resolving_submission() {
        val session = reconciliationSession()
        val otherRun = Run(RunId("other-run"), session.id, "succeeded")
        val otherHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("other", "assistant", "Other result", otherRun.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(otherHistory)
                statuses.add(otherRun)
                blockRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        sendTimeoutMillis = 50L,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(otherRun, opened.latestRun)
            assertTrue(opened.activeRuns.isEmpty())
            assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
            assertEquals(MessageSendErrorCategory.UNCERTAIN, opened.sendErrorCategory)
            assertTrue(opened.hasUnresolvedSubmission)
            assertEquals("Keep this draft", opened.composerText)
            assertEquals(listOf(otherRun.id), gateway.statusRequests)

            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun timeout_after_returning_to_the_session_list_is_recovered_when_the_session_reopens() {
        val session = reconciliationSession()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                blockRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        sendTimeoutMillis = 50L,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            assertTrue(gateway.runFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.submissionCompleted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isSending == false &&
                    it.sessionList?.openedSession?.latestRun == null &&
                    it.sessionList?.openedSession?.latestRunState == null &&
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                    it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }

            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)
            assertTrue(gateway.statusRequests.isEmpty())
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_refetches_and_remains_uncertain_without_resubmitting() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                statuses.add(run.copy(status = "running"))
                runs.add(run)
                observation =
                    ReconciliationThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta"),
                        ),
                    )
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
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
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation =
            ReconciliationBlockingObservation(
                listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta")),
            )
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("active", "assistant", "Partial", run.id, "running")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(activeHistory)
                histories.add(activeHistory)
                runs.add(run)
                statuses.add(run.copy(status = "running"))
                this.observation = observation
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
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }

            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertFalse(observation.closed.await(100, TimeUnit.MILLISECONDS))
            assertTrue(gateway.statusRequests.contains(run.id))
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_reconciliation_closes_the_retained_observer_before_a_later_run() {
        val session = reconciliationSession()
        val firstRun = Run(RunId("run-1"), session.id, "starting")
        val secondRun = Run(RunId("run-2"), session.id, "starting")
        val firstObservation = ReconciliationBlockingObservation()
        val secondObservation = ReconciliationBlockingObservation()
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("authoritative", "assistant", "Confirmed", firstRun.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(authoritativeHistory)
                statuses.add(firstRun.copy(status = "succeeded"))
                runs.add(firstRun)
                runs.add(secondRun)
                observations.add(firstObservation)
                observations.add(secondObservation)
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
            assertTrue(firstObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertTrue(firstObservation.closed.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == false
            }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(secondObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(firstRun.id, secondRun.id), gateway.observedRunIds)
        } finally {
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun cancelling_a_queued_observer_hands_off_to_the_next_run() {
        val session = reconciliationSession()
        val firstRun = Run(RunId("queued-run"), session.id, "running")
        val nextRun = Run(RunId("next-run"), session.id, "running")
        val dispatcher = ReconciliationPausingDispatcher()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                runs.add(firstRun)
                runs.add(nextRun)
                statuses.add(firstRun.copy(status = "succeeded"))
                statuses.add(nextRun)
                observation =
                    ReconciliationScriptedObservation(
                        listOf(
                            RunEvent(
                                RunEventType.MESSAGE_DELTA,
                                nextRun.id,
                                "running",
                                "Replacement event",
                                "delta",
                            ),
                        ),
                    )
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings = ReconciliationHolderSettings(dispatcher),
            )

        try {
            openReconciledSession(holder, gateway)
            dispatcher.paused = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            dispatcher.runNext()
            assertEquals(firstRun.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
            assertEquals(1, dispatcher.queuedCount)
            assertTrue(gateway.observedRunIds.isEmpty())

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            dispatcher.runLast()
            assertEquals(
                RunPresentationState.SUCCEEDED,
                holder.uiState.value.sessionList?.openedSession?.latestRunState,
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            dispatcher.runLast()
            assertEquals(nextRun.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
            assertEquals(2, gateway.runRequests.size)
            assertTrue(gateway.observedRunIds.isEmpty())

            dispatcher.drain()

            assertEquals(listOf(nextRun.id), gateway.observedRunIds)
            assertEquals("Replacement event", holder.uiState.value.sessionList?.openedSession?.activeResponse?.content)
        } finally {
            holder.close()
            dispatcher.drain()
        }
    }
}
