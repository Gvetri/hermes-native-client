package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunReconciliationRecoveryTest {
    @Test
    fun response_loss_with_terminal_and_active_discoveries_does_not_bind_an_unrelated_run() {
        val session = reconciliationSession()
        val terminalRun = Run(RunId("run-terminal-after-response-loss"), session.id, "succeeded")
        val activeRun = Run(RunId("run-unrelated-active"), session.id, "running")
        val history =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("terminal", "assistant", "Succeeded", terminalRun.id, "succeeded"),
                    GatewayHistoryMessage("active", "assistant", "Running", activeRun.id, "running"),
                ),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                statuses.add(activeRun)
                failRunCreation = true
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    opened.hasUnresolvedSubmission &&
                    opened.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED &&
                    opened.latestRun?.id == activeRun.id &&
                    !opened.isReconciliationInProgress
            }
            assertEquals(1, gateway.runRequests.size)
            assertEquals(listOf(terminalRun.id, activeRun.id), gateway.statusRequests)
            assertTrue(gateway.observedRunIds.isEmpty())

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    opened.hasUnresolvedSubmission &&
                    opened.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                    it.sessionList?.openedSession?.isSending == false
            }
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun unrelated_confirmed_reconciliation_preserves_send_failure_and_draft_before_reopen() {
        val session = reconciliationSession()
        val activeRun = Run(RunId("run-2"), session.id, "running")
        val terminalRun = activeRun.copy(status = "succeeded")
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("active", "assistant", "Running", activeRun.id, "running")),
            )
        val terminalHistory = succeededTerminalHistory(session, terminalRun)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(activeHistory)
                histories.add(terminalHistory)
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                statuses.add(terminalRun)
                statuses.add(terminalRun)
                failRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings = ReconciliationHolderSettings(Dispatchers.Default),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Failed first attempt"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.sendErrorCategory ==
                    MessageSendErrorCategory.GATEWAY_REQUEST_FAILED &&
                    it.sessionList?.openedSession?.composerText == "Failed first attempt" &&
                    gateway.statusRequests.isNotEmpty()
            }

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRun?.id == terminalRun.id &&
                    it.sessionList?.openedSession?.sendErrorCategory ==
                    MessageSendErrorCategory.GATEWAY_REQUEST_FAILED &&
                    it.sessionList?.openedSession?.composerText == "Failed first attempt"
            }
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun authoritative_terminal_status_closes_the_live_observer_and_settles_the_submission() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = ReconciliationBlockingObservation()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                statuses.add(run.copy(status = "cancelled"))
                statuses.add(run.copy(status = "cancelled"))
                statuses.add(run.copy(status = "cancelled"))
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
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isRefreshing == false &&
                    opened.latestRunState == RunPresentationState.CANCELLED &&
                    opened.hasUnresolvedSubmission == false
            }
            assertTrue(observation.closed.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            assertEquals(1, gateway.runRequests.size)
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun startup_recovery_load_failure_clears_refreshing_and_keeps_send_fail_closed() {
        val gateway = ReconciliationFakeGateway(reconciliationSession())
        val failingRegistry =
            object : RunRecoveryRegistry {
                override fun load(): List<RunRecoveryEntry> = error("recovery storage unavailable")

                override fun save(entry: RunRecoveryEntry) = Unit

                override fun remove(entry: RunRecoveryEntry) = Unit
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        Dispatchers.Default,
                        recoveryRegistry = failingRegistry,
                    ),
            )

        try {
            openReconciledSession(holder, gateway)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.let { opened ->
                    !opened.isRefreshing &&
                        !opened.isReconciliationInProgress &&
                        opened.hasUnresolvedSubmission &&
                        opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
                } == true
            }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not send while recovery is unavailable"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runRequests.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun one_failed_reconnect_recovery_entry_keeps_the_session_fail_closed_when_another_succeeds() {
        val session = reconciliationSession()
        val failedEntry = RunRecoveryEntry(session.id, RunId("run-recovery-failed"))
        val successfulRun = Run(RunId("run-recovery-success"), session.id, "succeeded")
        val successfulEntry = RunRecoveryEntry(session.id, successfulRun.id)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                failStatusRunIds += failedEntry.runId
                statuses.add(successfulRun)
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(
                    SessionHistory(
                        session.id,
                        listOf(
                            GatewayHistoryMessage(
                                "recovery-result",
                                "assistant",
                                "Recovered result",
                                successfulRun.id,
                                "succeeded",
                            ),
                        ),
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
            invokePersistedRecoveryEntry(holder, "https://gateway.example/profile", failedEntry)
            invokePersistedRecoveryEntry(holder, "https://gateway.example/profile", successfulEntry)
            awaitReconciliationCondition { gateway.statusRequests.size == 2 }
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.let { opened ->
                    !opened.isRefreshing &&
                        !opened.isReconciliationInProgress &&
                        opened.hasUnresolvedSubmission &&
                        opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
                } == true
            }
        } finally {
            holder.close()
        }
    }

    @Test
    fun overlapping_startup_and_open_recovery_keep_send_blocked_until_terminal_removal_finishes() {
        val session = reconciliationSession()
        val run = Run(RunId("terminal-recovery"), session.id, "succeeded")
        val entry = RunRecoveryEntry(session.id, run.id)
        val registry =
            ReconciliationBlockingRecoveryRegistry().apply {
                save(entry)
                blockRemovals = true
            }
        val gateway =
            ReconciliationFakeGateway(session).apply {
                statuses.add(run)
                statuses.add(run)
            }
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
            awaitReconciliationCondition { registry.removeRequests.get() == 1 }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationCondition { gateway.statusRequests.size == 2 }
            assertEquals(1, registry.removeRequests.get())
            assertEquals(listOf(entry), registry.load())
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runRequests.isEmpty())

            registry.releaseRemove.countDown()
            awaitReconciliationCondition { registry.load().isEmpty() }
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.let { opened ->
                    !opened.isReconciliationInProgress && !opened.isRefreshing && !opened.hasUnresolvedSubmission
                } == true
            }
            assertEquals(listOf(run.id, run.id), gateway.statusRequests)
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            registry.releaseRemove.countDown()
            holder.close()
            joinHolder(holder)
        }
    }

    @Test
    fun startup_and_late_recovery_claim_the_same_entry_only_once() {
        val session = reconciliationSession()
        val run = Run(RunId("run-recovery-claim"), session.id, "running")
        val recoveryRegistry = ReconciliationBlockingRecoveryRegistry()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                histories.add(SessionHistory(session.id, emptyList()))
            }
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
            gateway.blockRunCreation = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("local request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            recoveryRegistry.blockLoads = true
            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            assertTrue(recoveryRegistry.loadStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.blockNextStatus = true
            gateway.releaseRun.countDown()
            assertTrue(gateway.runFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            gateway.releaseStatus.countDown()
            assertTrue(gateway.statusFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            recoveryRegistry.releaseLoad.countDown()
            assertTrue(recoveryRegistry.loadFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            runBlocking {
                withTimeout(RECONCILIATION_TIMEOUT_MILLIS) {
                    delay(100)
                }
            }
            assertEquals(listOf(run.id), gateway.statusRequests)
        } finally {
            gateway.releaseRun.countDown()
            gateway.releaseStatus.countDown()
            recoveryRegistry.releaseLoad.countDown()
            holder.close()
        }
    }

    @Test
    fun refreshing_a_history_only_terminal_run_refetches_authoritative_status() {
        val session = reconciliationSession()
        val run = Run(RunId("run-history-only-refresh"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                repeat(4) { histories.add(history) }
                statuses.add(run)
                statuses.add(run)
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id)
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id, run.id) &&
                    gateway.historyRequests == 4
            }
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun reopening_a_history_only_terminal_run_refetches_authoritative_status() {
        val session = reconciliationSession()
        val run = Run(RunId("run-history-only-reopen"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                repeat(4) { histories.add(history) }
                statuses.add(run)
                statuses.add(run)
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id)
            }

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id, run.id) &&
                    gateway.historyRequests == 4
            }
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun invalid_authoritative_status_for_history_only_terminal_run_is_uncertain() {
        val session = reconciliationSession()
        val run = Run(RunId("run-history-only-invalid"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(history)
                statuses.add(Run(RunId("wrong-run"), session.id, "succeeded"))
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    opened.errorCategory == SessionHistoryErrorCategory.RECONCILIATION_FAILED &&
                    opened.isStale &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id) &&
                    gateway.historyRequests == 1
            }
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun stale_authoritative_status_for_history_only_terminal_run_is_uncertain() {
        val session = reconciliationSession()
        val run = Run(RunId("run-history-only-stale"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val observation = ReconciliationBlockingObservation()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(history)
                histories.add(history)
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
            assertTrue(observation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    opened.errorCategory == SessionHistoryErrorCategory.RECONCILIATION_FAILED &&
                    opened.isStale &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id) &&
                    gateway.historyRequests == 2 &&
                    gateway.observedRunIds == listOf(run.id)
            }
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun opening_a_history_only_terminal_run_refetches_authoritative_status() {
        val session = reconciliationSession()
        val run = Run(RunId("run-history-only"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(history)
                histories.add(history)
                statuses.add(run)
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id) &&
                    gateway.historyRequests == 2
            }
        } finally {
            holder.close()
        }
    }

    @Test
    fun failed_timeout_reconciliation_exposes_uncertainty_without_a_synthetic_run() {
        val session = reconciliationSession()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                failHistoryRequest = 2
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
            assertNull(opened.latestRun)
            assertNull(opened.latestRunState)
            assertTrue(opened.activeRuns.isEmpty())
            assertTrue(opened.hasUnresolvedSubmission)
            assertFalse(opened.isSending)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    private fun succeededTerminalHistory(
        session: Session,
        terminalRun: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(GatewayHistoryMessage("terminal", "assistant", "Succeeded", terminalRun.id, "succeeded")),
        )
}
