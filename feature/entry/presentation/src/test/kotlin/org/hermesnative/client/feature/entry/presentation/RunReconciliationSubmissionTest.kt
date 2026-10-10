package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.data.DefaultRunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunReconciliationSubmissionTest {
    @Test
    fun submission_replaces_the_queued_observer_started_by_open_reconciliation() {
        val session = reconciliationSession()
        val run = Run(RunId("overlapping-run"), session.id, "running")
        val registry = ReconciliationBlockingRecoveryRegistry()
        val dispatcher = ReconciliationPausingDispatcher(Dispatchers.Default)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                statuses.add(run)
                observation = recoveredDeltaObservation(run)
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        dispatcher,
                        recoveryRegistry = registry,
                        uncertaintyStore = DefaultRunSubmissionUncertaintyStore(ReconciliationUncertaintyStorage()),
                    ),
            )

        try {
            connectReconciliationGateway(holder, gateway)
            awaitReconciliationCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.blockLoads = true
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            assertTrue(registry.loadStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            registry.blockSaves = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("One submission"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(registry.saveStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            dispatcher.paused = true
            registry.releaseLoad.countDown()
            awaitReconciliationCondition { dispatcher.queuedCount == 1 }
            assertEquals(run.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
            assertTrue(registry.load().isEmpty())
            assertTrue(gateway.observedRunIds.isEmpty())
            registry.releaseSave.countDown()
            assertTrue(gateway.submissionCompleted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            dispatcher.drain()

            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertEquals(
                "Recovered observation",
                holder.uiState.value.sessionList?.openedSession?.activeResponse?.content,
            )
            assertEquals(listOf(RunRecoveryEntry(session.id, run.id)), registry.load())
            assertEquals(listOf(session.id to "One submission"), gateway.runRequests)
        } finally {
            registry.releaseLoad.countDown()
            registry.releaseSave.countDown()
            holder.close()
            dispatcher.drain()
            joinHolder(holder)
        }
    }

    @Test
    fun a_cancelled_queued_observer_cannot_remove_the_reopened_sessions_observer() {
        val session = reconciliationSession()
        val run = Run(RunId("reopened-run"), session.id, "running")
        val dispatcher = ReconciliationPausingDispatcher()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                statuses.add(run)
                observation =
                    ReconciliationScriptedObservation(
                        listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Reopened event", "delta")),
                    )
            }
        val holder =
            reconciliationHolder(
                gateway,
                settings =
                    ReconciliationHolderSettings(
                        dispatcher,
                    ),
            )
        try {
            openReconciledSession(holder, gateway)
            dispatcher.paused = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            dispatcher.runNext()
            assertEquals(1, dispatcher.queuedCount)
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            dispatcher.runLast()

            dispatcher.drain()

            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertEquals("Reopened event", holder.uiState.value.sessionList?.openedSession?.activeResponse?.content)
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
            dispatcher.drain()
        }
    }

    @Test
    fun a_cancelled_queued_observer_cannot_restart_after_connection_removal_or_close() {
        listOf(false, true).forEach { closeHolder ->
            val session = reconciliationSession()
            val run = Run(RunId("abandoned-run"), session.id, "running")
            val dispatcher = ReconciliationPausingDispatcher()
            val gateway = ReconciliationFakeGateway(session).apply { runs.add(run) }
            val holder =
                reconciliationHolder(
                    gateway,
                    settings =
                        ReconciliationHolderSettings(
                            dispatcher,
                        ),
                )
            try {
                openReconciledSession(holder, gateway)
                dispatcher.paused = true
                holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
                holder.onEvent(EntryUiEvent.SendMessageClicked)
                dispatcher.runNext()
                assertEquals(1, dispatcher.queuedCount)

                if (closeHolder) holder.close() else holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
                dispatcher.drain()

                assertTrue(gateway.observedRunIds.isEmpty())
                assertEquals(1, gateway.runRequests.size)
            } finally {
                holder.close()
                dispatcher.drain()
            }
        }
    }

    @Test(timeout = RECONCILIATION_TIMEOUT_MILLIS)
    fun direct_parent_cancellation_releases_a_queued_observer_without_restarting() {
        assertParentCancellationReleasesObserver(cancelDuringRegistration = false)
    }

    @Test(timeout = RECONCILIATION_TIMEOUT_MILLIS)
    fun direct_parent_cancellation_during_observer_registration_still_releases_ownership() {
        assertParentCancellationReleasesObserver(cancelDuringRegistration = true)
    }

    @Test
    fun a_blocked_observer_open_keeps_ownership_until_cancellation_finishes_before_next_run() {
        val session = reconciliationSession()
        val firstRun = Run(RunId("run-blocked-open-1"), session.id, "starting")
        val secondRun = Run(RunId("run-blocked-open-2"), session.id, "starting")
        val firstLateObservation = ReconciliationBlockingObservation()
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
                observations.add(firstLateObservation)
                observations.add(secondObservation)
                blockNextObservationOpen = true
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
            assertTrue(gateway.observationOpenStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    !it.sessionList!!.openedSession!!.isRefreshing
            }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertFalse(gateway.secondObservationRequested.await(100, TimeUnit.MILLISECONDS))

            gateway.releaseObservationOpen.countDown()
            assertTrue(gateway.observationOpenFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(firstLateObservation.closed.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.secondObservationRequested.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(secondObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(firstRun.id, secondRun.id), gateway.observedRunIds)
        } finally {
            gateway.releaseObservationOpen.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun reopening_an_observed_run_reconciles_before_resuming_observation() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "running")
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("message-1", "user", "Run this", run.id, "running")),
            )
        val firstObservation = ReconciliationBlockingObservation()
        val secondObservation = ReconciliationBlockingObservation()
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(activeHistory)
                histories.add(activeHistory)
                histories.add(activeHistory)
                histories.add(activeHistory)
                statuses.add(run)
                statuses.add(run)
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
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size == 1
            }
            assertTrue(firstObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }
            assertTrue(firstObservation.finished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size == 2 &&
                    gateway.historyRequests == 4
            }
            assertTrue(secondObservation.started.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(2, gateway.observedRunIds.size)

            assertEquals(listOf(run.id, run.id), gateway.observedRunIds)
        } finally {
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun returning_after_terminal_event_resolves_the_reopened_outcome_from_the_authoritative_status() {
        val session = reconciliationSession()
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                statuses.add(run.copy(status = "succeeded"))
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observation =
                    ReconciliationScriptedObservation(
                        listOf(RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded")),
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
            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }

            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size >= 2
            }
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)

            gateway.releaseStatus.countDown()
            assertTrue(gateway.statusFinished.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitReconciliationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
        } finally {
            gateway.releaseStatus.countDown()
            holder.close()
        }
    }

    @Test
    fun send_timeout_applies_authoritative_history_while_preserving_the_uncertain_draft() {
        val session = reconciliationSession()
        val otherRun = Run(RunId("other-run"), session.id, "succeeded")
        val otherHistory = otherRunHistory(session, otherRun)
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                repeat(4) { histories.add(otherHistory) }
                repeat(2) { statuses.add(otherRun) }
                blockRunCreation = true
            }
        val holder =
            reconciliationHolder(
                gateway,
                ReconciliationHolderSettings(dispatcher = Dispatchers.Default, sendTimeoutMillis = 50L),
            )

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RECONCILIATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            awaitReconciliationState(holder, timedOutSendResolvedState(otherRun))
            val opened = assertTimedOutSendKeptDraft(holder, gateway, session, otherRun)

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Edited after timeout"))
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitReconciliationState(holder, refreshedAfterTimeoutState(opened, otherRun))
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitReconciliationState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitReconciliationState(holder, reopenedAfterTimeoutState(otherRun))
            assertEquals(4, gateway.historyRequests)
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitReconciliationCondition { gateway.historyRequests == 5 }
            awaitReconciliationState(holder, sendAfterTimeoutState())
            assertReloadedTimeoutKeptDraft(holder, gateway, otherRun)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun response_loss_with_one_terminal_discovered_run_remains_uncertain() {
        val session = reconciliationSession()
        val run = Run(RunId("run-terminal-after-response-loss"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("terminal", "assistant", "Succeeded", run.id, "succeeded")),
            )
        val gateway =
            ReconciliationFakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(run)
                failRunCreation = true
            }
        val holder = reconciliationHolder(gateway)

        try {
            openReconciledSession(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitReconciliationState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    opened.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED &&
                    opened.hasUnresolvedSubmission &&
                    opened.composerText == "Keep this draft" &&
                    !opened.isReconciliationInProgress
            }
            assertEquals(1, gateway.runRequests.size)
            assertEquals(listOf(run.id), gateway.statusRequests)
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    private fun otherRunHistory(
        session: Session,
        otherRun: Run,
    ): SessionHistory =
        SessionHistory(
            session.id,
            listOf(
                GatewayHistoryMessage(
                    "other",
                    "assistant",
                    "Existing result",
                    otherRun.id,
                    "succeeded",
                    "External result",
                    "2026-09-08T21:00:00Z",
                ),
            ),
        )

    private fun timedOutSendResolvedState(otherRun: Run): (EntryUiState) -> Boolean =
        { state ->
            val opened = state.sessionList?.openedSession
            opened?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                !opened.isRefreshing &&
                !opened.isReconciliationInProgress &&
                opened.latestRun?.id == otherRun.id &&
                opened.latestRunState == RunPresentationState.SUCCEEDED &&
                opened.messages.map { message -> message.id } == listOf("other") &&
                opened.messages.single().runResult == "External result" &&
                opened.messages.single().timestamp?.toString() == "2026-09-08T21:00:00Z" &&
                opened.composerText == "Keep this draft"
        }

    private fun refreshedAfterTimeoutState(
        opened: OpenSessionUiState,
        otherRun: Run,
    ): (EntryUiState) -> Boolean =
        { state ->
            val refreshed = state.sessionList?.openedSession
            refreshed?.isRefreshing == false &&
                refreshed.latestRun?.id == otherRun.id &&
                refreshed.latestRunState == RunPresentationState.SUCCEEDED &&
                refreshed.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                refreshed.hasUnresolvedSubmission &&
                refreshed.composerText == "Edited after timeout" &&
                opened.messages.map { message -> message.id } == listOf("other") &&
                opened.messages.single().runResult == "External result" &&
                opened.messages.single().timestamp?.toString() == "2026-09-08T21:00:00Z"
        }

    private fun reopenedAfterTimeoutState(otherRun: Run): (EntryUiState) -> Boolean =
        { state ->
            state.sessionList?.openedSession?.isRefreshing == false &&
                state.sessionList?.openedSession?.latestRun?.id == otherRun.id &&
                state.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                state.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                state.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                state.sessionList?.openedSession?.isReconciliationInProgress == false &&
                state.sessionList?.openedSession?.composerText == "Edited after timeout"
        }

    private fun sendAfterTimeoutState(): (EntryUiState) -> Boolean =
        { state ->
            state.sessionList?.openedSession?.isReconciliationInProgress == false &&
                state.sessionList?.openedSession?.hasUnresolvedSubmission == true
        }

    private fun assertTimedOutSendKeptDraft(
        holder: EntryStateHolder,
        gateway: ReconciliationFakeGateway,
        session: Session,
        otherRun: Run,
    ): OpenSessionUiState {
        val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
        assertEquals("Keep this draft", opened.composerText)
        assertFalse(opened.isSending)
        assertEquals(otherRun.id, opened.latestRun?.id)
        assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
        assertTrue(opened.activeRuns.isEmpty())
        assertTrue(opened.hasUnresolvedSubmission)
        assertTrue(gateway.observedRunIds.isEmpty())
        val syntheticId = RunId("uncertain-send:${session.id.value}")
        assertFalse(gateway.statusRequests.contains(syntheticId))
        assertFalse(gateway.observedRunIds.contains(syntheticId))
        assertEquals(1, gateway.runRequests.size)
        assertEquals(2, gateway.historyRequests)
        assertEquals(listOf(otherRun.id), gateway.statusRequests)
        return opened
    }

    private fun assertReloadedTimeoutKeptDraft(
        holder: EntryStateHolder,
        gateway: ReconciliationFakeGateway,
        otherRun: Run,
    ) {
        assertEquals(1, gateway.runRequests.size)
        assertFalse(requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).isSending)
        assertEquals(listOf(otherRun.id, otherRun.id), gateway.statusRequests)
        assertTrue(gateway.observedRunIds.isEmpty())
        assertEquals(5, gateway.historyRequests)
    }
}
