package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class RunReconciliationDiscoveryTest {
    @Test
    fun a_same_text_external_run_does_not_resolve_a_timed_out_submission() {
        val session = reconciliationSession()
        val realRun = Run(RunId("run-later"), session.id, "succeeded")
        val realHistory =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("user", "user", "Keep this draft", realRun.id, "succeeded"),
                    GatewayHistoryMessage("assistant", "assistant", "Delivered", realRun.id, "succeeded"),
                ),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(realHistory)
                histories.add(realHistory)
                statuses.add(realRun)
                blockRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings = ReconciliationHolderSettings(Dispatchers.Default, sendTimeoutMillis = 50L),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) { state ->
                state.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isRefreshing == false &&
                    opened.latestRun?.id == realRun.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    opened.composerText == "Keep this draft"
            }
            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf("user", "assistant"), opened.messages.map { it.id })
            assertEquals(1, gateway.runRequests.size)
            assertTrue(gateway.observedRunIds.isEmpty())
            @Suppress("UNCHECKED_CAST")
            val localRuns = (privateField(holder, "sessionRuns") as Map<SessionId, List<Run>>)[session.id].orEmpty()
            assertTrue(localRuns.none { it.id == realRun.id })
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun an_old_same_text_message_does_not_resolve_an_unrelated_new_run() {
        val session = reconciliationSession()
        val baselineRun = Run(RunId("baseline-run"), session.id, "succeeded")
        val unrelatedRun = Run(RunId("unrelated-run"), session.id, "succeeded")
        val baselineHistory = baselineHistory(session, baselineRun)
        val laterHistory = historyWithUnrelatedResult(session, baselineRun, unrelatedRun)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(baselineHistory)
                histories.add(baselineHistory)
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(laterHistory)
                statuses.add(baselineRun)
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
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRun?.id == baselineRun.id &&
                    !it.sessionList?.openedSession!!.isRefreshing &&
                    !it.sessionList?.openedSession!!.isReconciliationInProgress
            }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == baselineRun.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRun?.id == unrelatedRun.id &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                    it.sessionList?.openedSession?.composerText == "Keep this draft"
            }
            assertEquals(listOf(baselineRun.id), gateway.statusRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_create_result_off_screen_is_reconciled_on_reopen_and_clears_sending_after_completion() {
        val session = reconciliationSession()
        val terminalRun = Run(RunId("run-off-screen"), session.id, "succeeded")
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", terminalRun.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                runs.add(terminalRun)
                blockRunCreation = true
                blockSubmissionCompletion = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings = ReconciliationHolderSettings(Dispatchers.Default),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run off screen"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            gateway.releaseRun.countDown()
            assertTrue(gateway.submissionCompleted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isSending == true
            }

            gateway.releaseSubmissionCompletion.countDown()
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isSending == false &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.latestRun?.id == terminalRun.id &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(listOf(terminalRun.id), gateway.statusRequests)
        } finally {
            gateway.releaseRun.countDown()
            gateway.releaseSubmissionCompletion.countDown()
            holder.close()
        }
    }

    @Test
    fun reopened_timed_out_send_keeps_same_text_external_run_authoritative_only() {
        val session = reconciliationSession()
        val correlatedRun = Run(RunId("run-correlated"), session.id, "running")
        val unrelatedRun = Run(RunId("run-unrelated"), session.id, "running")
        val history = timedOutSameTextHistory(session, correlatedRun, unrelatedRun)
        val correlatedObservation = ReconciliationBlockingObservation()
        val unrelatedObservation = ReconciliationBlockingObservation()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                histories.add(history)
                statuses.add(correlatedRun)
                statuses.add(unrelatedRun)
                observations.add(correlatedObservation)
                observations.add(unrelatedObservation)
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
                val opened = it.sessionList?.openedSession
                opened?.isSending == false &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.latestRun?.id == unrelatedRun.id &&
                    opened.latestRunState == RunPresentationState.RUNNING &&
                    opened.activeResponse == null &&
                    opened.hasUnresolvedSubmission
            }
            assertFalse(correlatedObservation.started.await(100, TimeUnit.MILLISECONDS))
            assertTrue(gateway.observedRunIds.none { it == correlatedRun.id })
            @Suppress("UNCHECKED_CAST")
            val localRuns = (privateField(holder, "sessionRuns") as Map<SessionId, List<Run>>)[session.id].orEmpty()
            assertTrue(localRuns.none { it.id == correlatedRun.id || it.id == unrelatedRun.id })
        } finally {
            gateway.releaseRun.countDown()
            correlatedObservation.release.countDown()
            unrelatedObservation.release.countDown()
            gateway.releaseSubmissionCompletion.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_observation_after_refresh_reconciles_with_the_current_request() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation =
            ReconciliationDelayedTerminalObservation(
                RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
            )
        val authoritativeHistory = authoritativeRefreshHistory(session, run)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "running"))
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observations.add(observation)
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings = ReconciliationHolderSettings(Dispatchers.Default),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }

            observation.release.countDown()
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.messages?.lastOrNull()?.content == "Confirmed after refresh"
            }
            assertEquals(listOf(run.id, run.id), gateway.statusRequests)
            assertEquals(4, gateway.historyRequests)
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun return_and_reopen_during_reconciliation_drops_the_stale_result() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "running")

        fun history(
            content: String,
            status: String,
        ) = SessionHistory(
            session.id,
            listOf(GatewayHistoryMessage("message-1", "assistant", content, run.id, status)),
        )
        val firstObservation = ReconciliationBlockingObservation()
        val secondObservation = ReconciliationBlockingObservation()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(history("initial-open", "running"))
                histories.add(history("initial-authoritative", "running"))
                histories.add(history("refresh-open", "running"))
                histories.add(history("reopen-open", "running"))
                histories.add(history("reopen-authoritative", "running"))
                histories.add(history("stale-refresh", "succeeded"))
                statuses.add(run.copy(status = "running"))
                statuses.add(run.copy(status = "succeeded"))
                statuses.add(run.copy(status = "running"))
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
            awaitReconciliationState(holder) { gateway.statusRequests.size == 1 && gateway.historyRequests == 2 }
            assertTrue(firstObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }
            assertTrue(firstObservation.finished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.messages?.lastOrNull()?.content == "reopen-open"
            }
            assertTrue(secondObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(3, gateway.statusRequests.size)
            assertEquals(5, gateway.historyRequests)
            assertEquals(2, gateway.observedRunIds.size)

            gateway.releaseStatus.countDown()
            assertStaleRefreshDropped(holder, gateway, "reopen-open")
        } finally {
            gateway.releaseStatus.countDown()
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun a_delayed_observer_unwind_queues_the_next_run_until_the_previous_job_finishes() {
        val session = reconciliationSession()
        val firstRun = Run(RunId("run-delayed-unwind-1"), session.id, "starting")
        val secondRun = Run(RunId("run-delayed-unwind-2"), session.id, "starting")
        val firstObservation = ReconciliationDelayedUnwindObservation()
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
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    !it.sessionList!!.openedSession!!.isRefreshing
            }
            assertTrue(firstObservation.closed.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertFalse(gateway.secondObservationRequested.await(100, TimeUnit.MILLISECONDS))

            firstObservation.release.countDown()
            assertTrue(firstObservation.finished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.secondObservationRequested.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(secondObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(firstRun.id, secondRun.id), gateway.observedRunIds)
        } finally {
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    private fun assertStaleRefreshDropped(
        holder: EntryStateHolder,
        gateway: ReconciliationFakeGateway,
        expectedContent: String,
    ) {
        assertTrue(gateway.statusFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertTrue(gateway.staleHistoryLoaded.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        awaitReconciliationState(holder) {
            gateway.staleHistoryLoaded.count == 0L &&
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                it.sessionList?.openedSession?.messages?.lastOrNull()?.content == expectedContent
        }
    }

    private fun timedOutSameTextHistory(
        session: Session,
        correlatedRun: Run,
        unrelatedRun: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(
                GatewayHistoryMessage("user", "user", "Keep this draft", correlatedRun.id, "running"),
                GatewayHistoryMessage("unrelated", "assistant", "Other work", unrelatedRun.id, "running"),
            ),
        )

    private fun baselineHistory(
        session: Session,
        baselineRun: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(
                GatewayHistoryMessage("baseline-user", "user", "Keep this draft", baselineRun.id, "succeeded"),
                GatewayHistoryMessage("baseline-assistant", "assistant", "Old result", baselineRun.id, "succeeded"),
            ),
        )

    private fun historyWithUnrelatedResult(
        session: Session,
        baselineRun: Run,
        unrelatedRun: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(
                GatewayHistoryMessage("baseline-user", "user", "Keep this draft", baselineRun.id, "succeeded"),
                GatewayHistoryMessage("baseline-assistant", "assistant", "Old result", baselineRun.id, "succeeded"),
                GatewayHistoryMessage(
                    "unrelated-assistant",
                    "assistant",
                    "Unrelated result",
                    unrelatedRun.id,
                    "succeeded",
                ),
            ),
        )

    private fun authoritativeRefreshHistory(
        session: Session,
        run: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(
                GatewayHistoryMessage(
                    "authoritative",
                    "assistant",
                    "Confirmed after refresh",
                    run.id,
                    "succeeded",
                ),
            ),
        )
}
