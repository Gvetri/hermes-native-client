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
    fun confirmed_cancellation_is_terminal_and_does_not_mark_the_run_uncertain() {
        val session = session()
        val run = Run(RunId("run-cancelled"), session.id, "starting")
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("cancelled", "assistant", "Cancelled", run.id, "cancelled")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "cancelled"))
                runs.add(run)
                observation =
                    ScriptedObservation(
                        listOf(RunEvent(RunEventType.COMPLETED, run.id, "cancelled", eventId = "cancelled")),
                    )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.CANCELLED &&
                    it.sessionList?.openedSession?.sendErrorCategory == null
            }
            assertEquals(1, gateway.runRequests.size)
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
        val observation =
            BlockingObservation(
                listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta")),
            )
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("active", "assistant", "Partial", run.id, "running")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(activeHistory)
                histories.add(activeHistory)
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
            awaitState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
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
    fun terminal_observation_after_refresh_reconciles_with_the_current_request() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = DelayedTerminalObservation(RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"))
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("authoritative", "assistant", "Confirmed after refresh", run.id, "succeeded")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(authoritativeHistory)
                statuses.add(run.copy(status = "running"))
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observations.add(observation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }

            observation.release.countDown()
            awaitState(holder) {
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
    fun terminal_reconciliation_closes_the_retained_observer_before_a_later_run() {
        val session = session()
        val firstRun = Run(RunId("run-1"), session.id, "starting")
        val secondRun = Run(RunId("run-2"), session.id, "starting")
        val firstObservation = BlockingObservation()
        val secondObservation = BlockingObservation()
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("authoritative", "assistant", "Confirmed", firstRun.id, "succeeded")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(authoritativeHistory)
                statuses.add(firstRun.copy(status = "succeeded"))
                runs.add(firstRun)
                runs.add(secondRun)
                observations.add(firstObservation)
                observations.add(secondObservation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(firstObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertTrue(firstObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(secondObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(firstRun.id, secondRun.id), gateway.observedRunIds)
        } finally {
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun reopening_an_observed_run_reconciles_before_resuming_observation() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "running")
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("message-1", "user", "Run this", run.id, "running")),
                null,
            )
        val firstObservation = BlockingObservation()
        val secondObservation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
                histories.add(activeHistory)
                histories.add(activeHistory)
                histories.add(activeHistory)
                histories.add(activeHistory)
                statuses.add(run)
                statuses.add(run)
                observations.add(firstObservation)
                observations.add(secondObservation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size == 1
            }
            assertTrue(firstObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            assertTrue(firstObservation.finished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size == 2 &&
                    gateway.historyRequests == 4
            }
            assertTrue(secondObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(2, gateway.observedRunIds.size)

            assertEquals(listOf(run.id, run.id), gateway.observedRunIds)
        } finally {
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun return_and_reopen_during_reconciliation_drops_the_stale_result() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "running")

        fun history(
            content: String,
            status: String,
        ) = SessionHistory(
            session.id,
            listOf(GatewayHistoryMessage("message-1", "assistant", content, run.id, status)),
            null,
        )
        val firstObservation = BlockingObservation()
        val secondObservation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
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
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            awaitState(holder) { gateway.statusRequests.size == 1 && gateway.historyRequests == 2 }
            assertTrue(firstObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            assertTrue(firstObservation.finished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.messages?.lastOrNull()?.content == "reopen-open"
            }
            assertTrue(secondObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(3, gateway.statusRequests.size)
            assertEquals(5, gateway.historyRequests)
            assertEquals(2, gateway.observedRunIds.size)

            gateway.releaseStatus.countDown()
            assertTrue(gateway.statusFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.staleHistoryLoaded.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
                gateway.staleHistoryLoaded.count == 0L &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.messages?.lastOrNull()?.content == "reopen-open"
            }
        } finally {
            gateway.releaseStatus.countDown()
            firstObservation.release.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun send_timeout_refetches_session_history_and_does_not_submit_again() {
        val session = session()
        val otherRun = Run(RunId("other-run"), session.id, "succeeded")
        val otherHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("other", "assistant", "Existing result", otherRun.id, "succeeded")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(otherHistory)
                histories.add(otherHistory)
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
            assertEquals(RunPresentationState.UNCERTAIN, opened.latestRunState)
            val uncertainRun = requireNotNull(opened.latestRun)
            assertEquals(listOf(uncertainRun), opened.activeRuns)
            assertTrue(gateway.observedRunIds.isEmpty())
            assertEquals(1, gateway.runRequests.size)
            assertEquals(2, gateway.historyRequests)

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.latestRun?.id != otherRun.id
            }
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)
            assertFalse(requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).isSending)
            assertTrue(gateway.statusRequests.isEmpty())
            assertTrue(gateway.observedRunIds.isEmpty())
            assertEquals(4, gateway.historyRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun confirmed_reconciliation_clears_send_failure_before_reopen() {
        val session = session()
        val activeRun = Run(RunId("run-2"), session.id, "running")
        val terminalRun = activeRun.copy(status = "succeeded")
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("active", "assistant", "Running", activeRun.id, "running")),
                null,
            )
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("terminal", "assistant", "Succeeded", terminalRun.id, "succeeded")),
                null,
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(activeHistory)
                histories.add(terminalHistory)
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                failRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Failed first attempt"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.sendErrorCategory == null &&
                    gateway.statusRequests.size == 1
            }

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRun?.id == terminalRun.id &&
                    it.sessionList?.openedSession?.sendErrorCategory == null
            }
            assertEquals(1, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_run_without_matching_history_closes_observer_and_keeps_send_disabled() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val otherRun = Run(RunId("run-2"), session.id, "running")
        val otherTerminalRun = otherRun.copy(status = "succeeded")
        val observation = BlockingObservation()
        val otherActiveHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("other-active", "assistant", "Other running", otherRun.id, "running")),
                null,
            )
        val otherTerminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("other-terminal", "assistant", "Other result", otherTerminalRun.id, "succeeded")),
                null,
            )
        val otherObservation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(SessionHistory(session.id, emptyList(), null))
                histories.add(otherActiveHistory)
                histories.add(otherActiveHistory)
                histories.add(otherTerminalHistory)
                histories.add(otherTerminalHistory)
                statuses.add(run.copy(status = "cancelled"))
                statuses.add(otherRun)
                statuses.add(otherTerminalRun)
                runs.add(run)
                observations.add(observation)
                observations.add(otherObservation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }
            assertTrue(observation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.latestRun?.id == run.id &&
                    gateway.statusRequests.size == 2
            }
            assertTrue(otherObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN &&
                    it.sessionList?.openedSession?.latestRun?.id == run.id &&
                    gateway.statusRequests.size == 3
            }
            assertTrue(otherObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not send"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)
            assertEquals(3, gateway.statusRequests.size)
            assertFalse(requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).isSending)
        } finally {
            observation.release.countDown()
            otherObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun failed_timeout_reconciliation_exposes_an_uncertain_run_state() {
        val session = session()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList(), null))
                failHistoryRequest = 2
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
            assertEquals(RunPresentationState.UNCERTAIN, opened.latestRunState)
            val uncertainRun = requireNotNull(opened.latestRun)
            assertEquals(listOf(uncertainRun), opened.activeRuns)
            assertFalse(opened.isSending)
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
        val observations = ArrayDeque<RunEventObservation>()
        var historyRequests = 0
        var observation: RunEventObservation = ScriptedObservation(emptyList())
        var blockRunCreation = false
        var failRunCreation = false
        var failHistoryRequest: Int? = null
        var blockNextStatus = false
        val runStarted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val statusStarted = CountDownLatch(1)
        val statusFinished = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val staleHistoryLoaded = CountDownLatch(1)

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            val (history, shouldFail) =
                synchronized(this) {
                    historyRequests += 1
                    val history =
                        if (histories.isEmpty()) {
                            SessionHistory(sessionId, emptyList(), null)
                        } else {
                            histories.removeFirst()
                        }
                    history to (historyRequests == failHistoryRequest)
                }
            if (shouldFail) error("history request failed")
            if (history.messages.any { it.content == "stale-refresh" }) {
                staleHistoryLoaded.countDown()
            }
            return history
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
            if (failRunCreation) error("run creation failed")
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
            val (shouldBlock, status) =
                synchronized(this) {
                    statusRequests += runId
                    val shouldBlock = blockNextStatus.also { blockNextStatus = false }
                    val status = if (statuses.isEmpty()) error("missing status") else statuses.removeFirst()
                    shouldBlock to status
                }
            if (shouldBlock) {
                statusStarted.countDown()
                try {
                    check(releaseStatus.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "Status was not released." }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                } finally {
                    statusFinished.countDown()
                }
            }
            return status
        }

        override fun observeRun(runId: RunId): RunEventObservation {
            return synchronized(this) {
                observedRunIds += runId
                if (observations.isEmpty()) observation else observations.removeFirst()
            }
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

    private class DelayedTerminalObservation(
        private val terminalEvent: RunEvent,
    ) : RunEventObservation {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var emitted = false

        override fun iterator(): Iterator<RunEvent> =
            object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    started.countDown()
                    try {
                        check(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "Observation was not released." }
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                    return !emitted
                }

                override fun next(): RunEvent {
                    check(!emitted) { "Terminal event was already emitted." }
                    emitted = true
                    return terminalEvent
                }
            }

        override fun close() {
            release.countDown()
        }
    }

    private class BlockingObservation(
        private val events: List<RunEvent> = emptyList(),
    ) : RunEventObservation {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val closeCount = AtomicInteger(0)

        override fun iterator(): Iterator<RunEvent> {
            started.countDown()
            var nextEventIndex = 0
            return object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    if (nextEventIndex < events.size) return true
                    return try {
                        release.await()
                        false
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    } finally {
                        finished.countDown()
                    }
                }

                override fun next(): RunEvent = events[nextEventIndex++]
            }
        }

        override fun close() {
            release.countDown()
            if (closeCount.incrementAndGet() == 1) closed.countDown()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
