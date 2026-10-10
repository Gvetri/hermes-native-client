package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.DefaultRunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.data.RunSubmissionUncertaintyStorage
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
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
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RunReconciliationStateHolderTest {
    @Test
    fun history_run_selection_is_atomic_with_unresolved_submission_state() {
        val session = session()
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
            BlockingContainsMap(
                delegate = unresolvedRuns,
                entered = containsEntered,
                release = releaseContains,
            )
        val gateway = FakeGateway(session)
        val holder = holder(gateway)
        val lock = privateField(holder, "sessionRequestLock")
        privateField(holder, "uncertainSubmissionRunIds", blockingRuns)
        val selectedRun = AtomicReference<RunId?>()
        val selectionFailure = AtomicReference<Throwable?>()
        val selector =
            Thread {
                try {
                    selectedRun.set(invokeHistoryRunIdToReconcile(holder, session.id, openedSession))
                } catch (error: Throwable) {
                    selectionFailure.set(error)
                }
            }
        val mutation =
            Thread {
                synchronized(lock) {
                    unresolvedRuns[session.id] = RunId("unresolved-run")
                    mutationFinished.countDown()
                }
            }

        try {
            selector.start()
            assertTrue(containsEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            mutation.start()
            assertFalse(mutationFinished.await(100, TimeUnit.MILLISECONDS))

            releaseContains.countDown()
            selector.join(TEST_TIMEOUT_MILLIS)
            mutation.join(TEST_TIMEOUT_MILLIS)

            assertFalse(selector.isAlive)
            assertFalse(mutation.isAlive)
            assertNull(selectionFailure.get())
            assertEquals(latestRun.id, selectedRun.get())
        } finally {
            releaseContains.countDown()
            if (selector.isAlive) selector.join(TEST_TIMEOUT_MILLIS)
            if (mutation.isAlive) mutation.join(TEST_TIMEOUT_MILLIS)
            holder.close()
        }
    }

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
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
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
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
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
    fun terminal_sse_event_blocks_send_until_authoritative_reconciliation_finishes() {
        val session = session()
        val run = Run(RunId("run-terminal-race"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(run)
                blockNextStatus = true
                observation =
                    ScriptedObservation(
                        listOf(RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "done")),
                    )
            }
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = recoveryRegistry)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == true
            }
            assertEquals(listOf(RunRecoveryEntry(session.id, run.id)), recoveryRegistry.load())

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(listOf(session.id to "First"), gateway.runRequests)

            gateway.releaseStatus.countDown()
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-list-race"), session.id, "succeeded")
        val gateway =
            FakeGateway(session).apply {
                histories.add(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
                    ),
                )
                statuses.add(run)
                runs.add(run)
            }
        val registry = ArmingRunRecoveryRegistry()
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = registry)

        try {
            connect(holder, gateway)
            awaitCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.armed = true

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            assertTrue(registry.entered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) { state ->
                val opened = state.sessionList?.openedSession
                opened != null &&
                    opened.session.id == session.id &&
                    opened.isReconciliationInProgress &&
                    !opened.isRefreshing
            }

            val listRequestsBefore = gateway.listRequests
            holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
            val listLoadedOverTheConversation = settles { gateway.listRequests > listRequestsBefore }
            registry.release.countDown()

            assertFalse(
                "the list pane refresh replaced the conversation's in-flight request",
                listLoadedOverTheConversation,
            )
            awaitState(holder) { state ->
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
        val session = session()
        val run = Run(RunId("run-create-race"), session.id, "succeeded")
        val gateway =
            FakeGateway(session).apply {
                histories.add(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
                    ),
                )
                statuses.add(run)
                runs.add(run)
            }
        val registry = ArmingRunRecoveryRegistry(failAfterRelease = true)
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = registry)

        try {
            connect(holder, gateway)
            awaitCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.armed = true

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            assertTrue(registry.entered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) { it.sessionList?.openedSession?.isReconciliationInProgress == true }

            val replacedRequest = privateField(holder, "sessionJob") as? Job
            holder.onEvent(EntryUiEvent.CreateSessionClicked)
            awaitState(holder) { it.sessionList?.createSession != null }
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
        val session = session()
        val run = Run(RunId("run-terminal-visible"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(run)
                blockNextStatus = true
                observation =
                    ScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "done"),
                        ),
                    )
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            val pending = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(pending.isReconciliationInProgress)
            assertTrue(pending.isRefreshing)
            assertTrue(pending.isStale)
            assertEquals("Partial", pending.activeResponse?.content)
            assertTrue(pending.messages.isEmpty())

            gateway.releaseStatus.countDown()
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-preserve-response"), session.id, "starting")
        val terminalRun = run.copy(status = "succeeded")
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val observation =
            BlockingObservation(
                listOf(
                    RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                    RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                ),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                runs.add(run)
                observations.add(observation)
                histories.add(terminalHistory)
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                blockNextStatus = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == true &&
                    it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }

            gateway.releaseStatus.countDown()
            awaitState(holder) {
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

    @Test
    fun a_same_text_external_run_does_not_resolve_a_timed_out_submission() {
        val session = session()
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
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(realHistory)
                histories.add(realHistory)
                statuses.add(realRun)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) { state ->
                state.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
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
        val session = session()
        val baselineRun = Run(RunId("baseline-run"), session.id, "succeeded")
        val unrelatedRun = Run(RunId("unrelated-run"), session.id, "succeeded")
        val baselineHistory =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("baseline-user", "user", "Keep this draft", baselineRun.id, "succeeded"),
                    GatewayHistoryMessage("baseline-assistant", "assistant", "Old result", baselineRun.id, "succeeded"),
                ),
            )
        val laterHistory =
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
        val gateway =
            FakeGateway(session).apply {
                histories.add(baselineHistory)
                histories.add(baselineHistory)
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(laterHistory)
                statuses.add(baselineRun)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRun?.id == baselineRun.id &&
                    !it.sessionList?.openedSession!!.isRefreshing &&
                    !it.sessionList?.openedSession!!.isReconciliationInProgress
            }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == baselineRun.id &&
                    opened.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
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
        val session = session()
        val terminalRun = Run(RunId("run-off-screen"), session.id, "succeeded")
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", terminalRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                runs.add(terminalRun)
                blockRunCreation = true
                blockSubmissionCompletion = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run off screen"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            gateway.releaseRun.countDown()
            assertTrue(gateway.submissionCompleted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.isSending == true
            }

            gateway.releaseSubmissionCompletion.countDown()
            awaitState(holder) {
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
        val session = session()
        val correlatedRun = Run(RunId("run-correlated"), session.id, "running")
        val unrelatedRun = Run(RunId("run-unrelated"), session.id, "running")
        val history =
            SessionHistory(
                session.id,
                listOf(
                    GatewayHistoryMessage("user", "user", "Keep this draft", correlatedRun.id, "running"),
                    GatewayHistoryMessage("unrelated", "assistant", "Other work", unrelatedRun.id, "running"),
                ),
            )
        val correlatedObservation = BlockingObservation()
        val unrelatedObservation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                histories.add(history)
                statuses.add(correlatedRun)
                statuses.add(unrelatedRun)
                observations.add(correlatedObservation)
                observations.add(unrelatedObservation)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.submissionCompleted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
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
    fun timed_out_external_run_does_not_resolve_the_unresolved_submission() {
        val session = session()
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
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(submittedRun)
                statuses.add(laterRun)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-stale-history"), session.id, "running")
        val staleTerminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("stale", "assistant", "Stale result", run.id, "succeeded")),
            )
        val observation =
            BlockingObservation(
                listOf(
                    RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                    RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                ),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(staleTerminalHistory)
                histories.add(staleTerminalHistory)
                statuses.add(run)
                runs.add(run)
                observations.add(observation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.activeResponse?.content == "Partial"
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-stale-terminal-history"), session.id, "starting")
        val staleHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("stale-user", "user", "Run this")),
            )
        val observation =
            BlockingObservation(
                listOf(
                    RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                    RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "partial"),
                ),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(staleHistory)
                histories.add(staleHistory)
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observations.add(observation)
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) { it.sessionList?.openedSession?.activeResponse?.content == "Partial" }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
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
        val session = session()
        val terminalRun = Run(RunId("run-created-terminal"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Created result", terminalRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                runs.add(terminalRun)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Create terminal"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
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
        val session = session()
        val otherRun = Run(RunId("other-run"), session.id, "succeeded")
        val otherHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("other", "assistant", "Other result", otherRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(otherHistory)
                statuses.add(otherRun)
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
        val session = session()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.submissionCompleted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
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
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
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
        val observation =
            DelayedTerminalObservation(
                RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
            )
        val authoritativeHistory =
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
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
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
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
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
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == false
            }

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
    fun cancelling_a_queued_observer_hands_off_to_the_next_run() {
        val session = session()
        val firstRun = Run(RunId("queued-run"), session.id, "running")
        val nextRun = Run(RunId("next-run"), session.id, "running")
        val dispatcher = PausingDispatcher()
        val gateway =
            FakeGateway(session).apply {
                runs.add(firstRun)
                runs.add(nextRun)
                statuses.add(firstRun.copy(status = "succeeded"))
                statuses.add(nextRun)
                observation =
                    ScriptedObservation(
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
        val holder = holder(gateway, dispatcher)

        try {
            open(holder, gateway)
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

    @Test
    fun submission_replaces_the_queued_observer_started_by_open_reconciliation() {
        val session = session()
        val run = Run(RunId("overlapping-run"), session.id, "running")
        val registry = BlockingRecoveryRegistry()
        val dispatcher = PausingDispatcher(Dispatchers.Default)
        val gateway =
            FakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                statuses.add(run)
                observation = recoveredDeltaObservation(run)
            }
        val holder =
            holder(
                gateway,
                dispatcher,
                recoveryRegistry = registry,
                uncertaintyStore = DefaultRunSubmissionUncertaintyStore(InMemoryUncertaintyStorage()),
            )

        try {
            connect(holder, gateway)
            awaitCondition { privateField(holder, "recoveryLoadPending") == false }
            registry.blockLoads = true
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            assertTrue(registry.loadStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            registry.blockSaves = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("One submission"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(registry.saveStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            dispatcher.paused = true
            registry.releaseLoad.countDown()
            awaitCondition { dispatcher.queuedCount == 1 }
            assertEquals(run.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
            assertTrue(registry.load().isEmpty())
            assertTrue(gateway.observedRunIds.isEmpty())
            registry.releaseSave.countDown()
            assertTrue(gateway.submissionCompleted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

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
        val session = session()
        val run = Run(RunId("reopened-run"), session.id, "running")
        val dispatcher = PausingDispatcher()
        val gateway =
            FakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                statuses.add(run)
                observation =
                    ScriptedObservation(
                        listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Reopened event", "delta")),
                    )
            }
        val holder = holder(gateway, dispatcher)
        try {
            open(holder, gateway)
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
            val session = session()
            val run = Run(RunId("abandoned-run"), session.id, "running")
            val dispatcher = PausingDispatcher()
            val gateway = FakeGateway(session).apply { runs.add(run) }
            val holder = holder(gateway, dispatcher)
            try {
                open(holder, gateway)
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

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun direct_parent_cancellation_releases_a_queued_observer_without_restarting() {
        assertParentCancellationReleasesObserver(cancelDuringRegistration = false)
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun direct_parent_cancellation_during_observer_registration_still_releases_ownership() {
        assertParentCancellationReleasesObserver(cancelDuringRegistration = true)
    }

    private fun assertParentCancellationReleasesObserver(cancelDuringRegistration: Boolean) {
        val session = session()
        val run = Run(RunId("parent-cancelled-run"), session.id, "running")
        val dispatcher = PausingDispatcher()
        val parent = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(parent + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
        val gateway = FakeGateway(session).apply { runs.add(run) }
        val holder = holder(gateway, scope = scope)
        val registrations = AtomicInteger(0)
        val observerJobs =
            object : LinkedHashMap<SessionId, Job>() {
                override fun put(
                    key: SessionId,
                    value: Job,
                ): Job? {
                    check(registrations.incrementAndGet() <= 3) {
                        "Observer restart did not stop after parent cancellation."
                    }
                    val previous = super.put(key, value)
                    if (cancelDuringRegistration) parent.cancel()
                    return previous
                }
            }
        privateField(holder, "runObservationJobs", observerJobs)

        try {
            open(holder, gateway)
            dispatcher.paused = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Cancel the parent"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            dispatcher.runNext()
            if (!cancelDuringRegistration) {
                assertEquals(1, dispatcher.queuedCount)
                assertTrue(observerJobs.values.single().isActive)
            }

            scope.cancel()
            dispatcher.drain()
            runBlocking { withTimeout(TEST_TIMEOUT_MILLIS) { parent.join() } }

            assertEquals("A cancelled parent must not register replacement observers.", 1, registrations.get())
            assertTrue("Unexpected coroutine failures: $failures", failures.isEmpty())
            assertTrue(parent.isCompleted)
            assertTrue(observerJobs.isEmpty())
            assertTrue((privateField(holder, "runObservationRunIds") as Map<*, *>).isEmpty())
            assertTrue((privateField(holder, "pendingRunObservationRequests") as Map<*, *>).isEmpty())
            assertTrue(gateway.observedRunIds.isEmpty())
            assertEquals(listOf(session.id to "Cancel the parent"), gateway.runRequests)
            assertEquals(run.id, holder.uiState.value.sessionList?.openedSession?.latestRun?.id)
        } finally {
            holder.close()
            dispatcher.drain()
            joinHolder(holder)
        }
    }

    @Test
    fun a_blocked_observer_open_keeps_ownership_until_cancellation_finishes_before_next_run() {
        val session = session()
        val firstRun = Run(RunId("run-blocked-open-1"), session.id, "starting")
        val secondRun = Run(RunId("run-blocked-open-2"), session.id, "starting")
        val firstLateObservation = BlockingObservation()
        val secondObservation = BlockingObservation()
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("authoritative", "assistant", "Confirmed", firstRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
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
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.observationOpenStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    !it.sessionList!!.openedSession!!.isRefreshing
            }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertFalse(gateway.secondObservationRequested.await(100, TimeUnit.MILLISECONDS))

            gateway.releaseObservationOpen.countDown()
            assertTrue(gateway.observationOpenFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(firstLateObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.secondObservationRequested.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(secondObservation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(firstRun.id, secondRun.id), gateway.observedRunIds)
        } finally {
            gateway.releaseObservationOpen.countDown()
            secondObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun a_delayed_observer_unwind_queues_the_next_run_until_the_previous_job_finishes() {
        val session = session()
        val firstRun = Run(RunId("run-delayed-unwind-1"), session.id, "starting")
        val secondRun = Run(RunId("run-delayed-unwind-2"), session.id, "starting")
        val firstObservation = DelayedUnwindObservation()
        val secondObservation = BlockingObservation()
        val authoritativeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("authoritative", "assistant", "Confirmed", firstRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
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
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    !it.sessionList!!.openedSession!!.isRefreshing
            }
            assertTrue(firstObservation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertFalse(gateway.secondObservationRequested.await(100, TimeUnit.MILLISECONDS))

            firstObservation.release.countDown()
            assertTrue(firstObservation.finished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.secondObservationRequested.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
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
    fun returning_after_terminal_event_resolves_the_reopened_outcome_from_the_authoritative_status() {
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(SessionHistory(session.id, emptyList()))
                statuses.add(run.copy(status = "succeeded"))
                statuses.add(run.copy(status = "succeeded"))
                runs.add(run)
                observation =
                    ScriptedObservation(
                        listOf(RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded")),
                    )
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }

            gateway.blockNextStatus = true
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    gateway.statusRequests.size >= 2
            }
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(1, gateway.runRequests.size)

            gateway.releaseStatus.countDown()
            assertTrue(gateway.statusFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
        } finally {
            gateway.releaseStatus.countDown()
            holder.close()
        }
    }

    @Test
    fun send_timeout_applies_authoritative_history_while_preserving_the_uncertain_draft() {
        val session = session()
        val otherRun = Run(RunId("other-run"), session.id, "succeeded")
        val otherHistory =
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
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(otherHistory)
                histories.add(otherHistory)
                histories.add(otherHistory)
                histories.add(otherHistory)
                statuses.add(otherRun)
                statuses.add(otherRun)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default, sendTimeoutMillis = 50L)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            awaitState(holder) {
                val opened = it.sessionList?.openedSession
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

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Edited after timeout"))
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                val refreshed = it.sessionList?.openedSession
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
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                it.sessionList?.openedSession?.isRefreshing == false &&
                    it.sessionList?.openedSession?.latestRun?.id == otherRun.id &&
                    it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED &&
                    it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true &&
                    it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.composerText == "Edited after timeout"
            }
            assertEquals(4, gateway.historyRequests)
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitCondition { gateway.historyRequests == 5 }
            awaitState(holder) {
                it.sessionList?.openedSession?.isReconciliationInProgress == false &&
                    it.sessionList?.openedSession?.hasUnresolvedSubmission == true
            }
            assertEquals(1, gateway.runRequests.size)
            assertFalse(requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).isSending)
            assertEquals(listOf(otherRun.id, otherRun.id), gateway.statusRequests)
            assertTrue(gateway.observedRunIds.isEmpty())
            assertEquals(5, gateway.historyRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun response_loss_with_one_terminal_discovered_run_remains_uncertain() {
        val session = session()
        val run = Run(RunId("run-terminal-after-response-loss"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("terminal", "assistant", "Succeeded", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(run)
                failRunCreation = true
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
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

    @Test
    fun response_loss_with_terminal_and_active_discoveries_does_not_bind_an_unrelated_run() {
        val session = session()
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
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(history)
                statuses.add(terminalRun)
                statuses.add(activeRun)
                failRunCreation = true
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
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
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    opened.hasUnresolvedSubmission &&
                    opened.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
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
        val session = session()
        val activeRun = Run(RunId("run-2"), session.id, "running")
        val terminalRun = activeRun.copy(status = "succeeded")
        val activeHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("active", "assistant", "Running", activeRun.id, "running")),
            )
        val terminalHistory =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("terminal", "assistant", "Succeeded", terminalRun.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
                histories.add(activeHistory)
                histories.add(terminalHistory)
                histories.add(terminalHistory)
                statuses.add(terminalRun)
                statuses.add(terminalRun)
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
                    it.sessionList?.openedSession?.sendErrorCategory ==
                    MessageSendErrorCategory.GATEWAY_REQUEST_FAILED &&
                    it.sessionList?.openedSession?.composerText == "Failed first attempt" &&
                    gateway.statusRequests.isNotEmpty()
            }

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
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
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.isRefreshing == false &&
                    opened.latestRunState == RunPresentationState.CANCELLED &&
                    opened.hasUnresolvedSubmission == false
            }
            assertTrue(observation.closed.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            assertEquals(1, gateway.runRequests.size)
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun startup_recovery_load_failure_clears_refreshing_and_keeps_send_fail_closed() {
        val gateway = FakeGateway(session())
        val failingRegistry =
            object : RunRecoveryRegistry {
                override fun load(): List<RunRecoveryEntry> = error("recovery storage unavailable")

                override fun save(entry: RunRecoveryEntry) = Unit

                override fun remove(entry: RunRecoveryEntry) = Unit
            }
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = failingRegistry)

        try {
            open(holder, gateway)
            awaitState(holder) {
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
        val session = session()
        val failedEntry = RunRecoveryEntry(session.id, RunId("run-recovery-failed"))
        val successfulRun = Run(RunId("run-recovery-success"), session.id, "succeeded")
        val successfulEntry = RunRecoveryEntry(session.id, successfulRun.id)
        val gateway =
            FakeGateway(session).apply {
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
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            invokePersistedRecoveryEntry(holder, "https://gateway.example/profile", failedEntry)
            invokePersistedRecoveryEntry(holder, "https://gateway.example/profile", successfulEntry)
            awaitCondition { gateway.statusRequests.size == 2 }
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("terminal-recovery"), session.id, "succeeded")
        val entry = RunRecoveryEntry(session.id, run.id)
        val registry =
            BlockingRecoveryRegistry().apply {
                save(entry)
                blockRemovals = true
            }
        val gateway =
            FakeGateway(session).apply {
                statuses.add(run)
                statuses.add(run)
            }
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = registry)
        try {
            connect(holder, gateway)
            awaitCondition { registry.removeRequests.get() == 1 }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitCondition { gateway.statusRequests.size == 2 }
            assertEquals(1, registry.removeRequests.get())
            assertEquals(listOf(entry), registry.load())
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runRequests.isEmpty())

            registry.releaseRemove.countDown()
            awaitCondition { registry.load().isEmpty() }
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-recovery-claim"), session.id, "running")
        val recoveryRegistry = BlockingRecoveryRegistry()
        val gateway =
            FakeGateway(session).apply {
                runs.add(run)
                statuses.add(run)
                histories.add(SessionHistory(session.id, emptyList()))
            }
        val holder = holder(gateway, Dispatchers.Default, recoveryRegistry = recoveryRegistry)

        try {
            open(holder, gateway)
            gateway.blockRunCreation = true
            holder.onEvent(EntryUiEvent.ComposerTextChanged("local request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            recoveryRegistry.blockLoads = true
            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            assertTrue(recoveryRegistry.loadStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.blockNextStatus = true
            gateway.releaseRun.countDown()
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(gateway.statusStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            gateway.releaseStatus.countDown()
            assertTrue(gateway.statusFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            recoveryRegistry.releaseLoad.countDown()
            assertTrue(recoveryRegistry.loadFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
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
        val session = session()
        val run = Run(RunId("run-history-only-refresh"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                repeat(4) { histories.add(history) }
                statuses.add(run)
                statuses.add(run)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id)
            }

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-history-only-reopen"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                repeat(4) { histories.add(history) }
                statuses.add(run)
                statuses.add(run)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened != null &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress &&
                    gateway.statusRequests == listOf(run.id)
            }

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            awaitState(holder) { it.sessionList?.openedSession == null }
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-history-only-invalid"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(history)
                statuses.add(Run(RunId("wrong-run"), session.id, "succeeded"))
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-history-only-stale"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val observation = BlockingObservation()
        val gateway =
            FakeGateway(session).apply {
                histories.add(history)
                histories.add(history)
                statuses.add(run.copy(status = "running"))
                this.observation = observation
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway)
            assertTrue(observation.started.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            awaitState(holder) {
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
        val session = session()
        val run = Run(RunId("run-history-only"), session.id, "succeeded")
        val history =
            SessionHistory(
                session.id,
                listOf(GatewayHistoryMessage("result", "assistant", "Confirmed", run.id, "succeeded")),
            )
        val gateway =
            FakeGateway(session).apply {
                histories.add(history)
                histories.add(history)
                statuses.add(run)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway)
            awaitState(holder) {
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
        val session = session()
        val gateway =
            FakeGateway(session).apply {
                histories.add(SessionHistory(session.id, emptyList()))
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

    private fun connect(
        holder: EntryStateHolder,
        gateway: FakeGateway,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
    }

    private fun settles(predicate: () -> Boolean): Boolean {
        val appeared =
            runBlocking {
                try {
                    withTimeout(SETTLE_MILLIS) {
                        while (!predicate()) delay(5)
                    }
                    true
                } catch (_: TimeoutCancellationException) {
                    false
                }
            }
        return appeared || predicate()
    }

    @Suppress("UNCHECKED_CAST")
    private fun recoveryUnavailableSessions(holder: EntryStateHolder): Set<SessionId> =
        privateField(holder, "recoveryUnavailableSessions") as Set<SessionId>

    private class ArmingRunRecoveryRegistry(
        private val failAfterRelease: Boolean = false,
    ) : RunRecoveryRegistry {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var armed = false

        override fun load(): List<RunRecoveryEntry> {
            if (!armed) return emptyList()
            entered.countDown()
            check(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "recovery load was not released." }
            if (failAfterRelease) error("recovery registry is unavailable")
            return emptyList()
        }

        override fun save(entry: RunRecoveryEntry) = Unit

        override fun remove(entry: RunRecoveryEntry) = Unit
    }

    private fun holder(
        gateway: FakeGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        sendTimeoutMillis: Long = 30_000L,
        recoveryRegistry: RunRecoveryRegistry? = null,
        uncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    ): EntryStateHolder {
        val repository: GatewayConnectionRepository =
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        return EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
                },
            scope = scope,
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            dependencies =
                EntryStateHolderDependencies(
                    runRecoveryRegistry = recoveryRegistry,
                    runSubmissionUncertaintyStore = uncertaintyStore,
                    persistRunRecoveryEntry =
                        recoveryRegistry?.let { registry -> { _, entry -> registry.save(entry) } },
                    removeRunRecoveryEntry =
                        recoveryRegistry?.let { registry -> { _, entry -> registry.remove(entry) } },
                    removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
                    onRunSubmissionCompleted = {
                        gateway.submissionCompleted.countDown()
                        if (gateway.blockSubmissionCompletion) {
                            check(
                                gateway.releaseSubmissionCompletion.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                            ) {
                                "Submission completion was not released."
                            }
                        }
                    },
                    sendTimeoutMillis = sendTimeoutMillis,
                ),
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
        awaitState(holder) {
            it.sessionList?.openedSession?.session?.id == gateway.session.id &&
                !it.sessionList!!.openedSession!!.isReconciliationInProgress
        }
    }

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (EntryUiState) -> Boolean,
    ) {
        runBlocking {
            try {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    holder.uiState.first(predicate)
                }
            } catch (error: Throwable) {
                throw AssertionError("state=${holder.uiState.value}", error)
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

    private fun recoveredDeltaObservation(run: Run): ScriptedObservation =
        ScriptedObservation(
            listOf(RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Recovered observation", "delta")),
        )

    private fun joinHolder(holder: EntryStateHolder) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                (privateField(holder, "scope") as CoroutineScope).coroutineContext[Job]?.join()
            }
        }
    }

    private fun session(): Session =
        Session(
            id = SessionId("session-1"),
            title = "Session",
            preview = "Preview",
            pinned = false,
        )

    private fun invokePersistedRecoveryEntry(
        holder: EntryStateHolder,
        endpoint: String,
        entry: RunRecoveryEntry,
    ) {
        EntryStateHolder::class.java
            .getDeclaredMethod(
                "reconcilePersistedRecoveryEntryIfConnected",
                String::class.java,
                RunRecoveryEntry::class.java,
            ).apply { isAccessible = true }
            .invoke(holder, endpoint, entry)
    }

    private class FakeGateway(
        val session: Session,
    ) : SessionGatewayPort, RunGatewayPort {
        val histories = ArrayDeque<SessionHistory>()
        val statuses = ArrayDeque<Run>()
        val runs = ArrayDeque<Run>()
        val runRequests: MutableList<Pair<SessionId, String>> = CopyOnWriteArrayList()
        val statusRequests: MutableList<RunId> = CopyOnWriteArrayList()
        val failStatusRunIds = mutableSetOf<RunId>()
        val observedRunIds: MutableList<RunId> = CopyOnWriteArrayList()
        val observations = ArrayDeque<RunEventObservation>()
        private val historyRequestCount = AtomicInteger(0)
        val historyRequests: Int get() = historyRequestCount.get()
        private val listRequestCount = AtomicInteger(0)
        val listRequests: Int get() = listRequestCount.get()
        var observation: RunEventObservation = ScriptedObservation(emptyList())
        var blockRunCreation = false
        var failRunCreation = false
        var failHistoryRequest: Int? = null
        var blockNextStatus = false
        var blockSubmissionCompletion = false
        var blockNextObservationOpen = false
        val runStarted = CountDownLatch(1)
        val submissionCompleted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val releaseSubmissionCompletion = CountDownLatch(1)
        val statusStarted = CountDownLatch(1)
        val statusFinished = CountDownLatch(1)
        val releaseStatus = CountDownLatch(1)
        val staleHistoryLoaded = CountDownLatch(1)
        val observationOpenStarted = CountDownLatch(1)
        val observationOpenFinished = CountDownLatch(1)
        val releaseObservationOpen = CountDownLatch(1)
        val secondObservationRequested = CountDownLatch(1)
        private val observationRequests = mutableListOf<RunId>()

        override fun listSessions(request: SessionListRequest): SessionPage {
            listRequestCount.incrementAndGet()
            return SessionPage(listOf(session), null)
        }

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            val (history, shouldFail) =
                synchronized(this) {
                    historyRequestCount.incrementAndGet()
                    val history =
                        if (histories.isEmpty()) {
                            SessionHistory(sessionId, emptyList())
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
                    if (failStatusRunIds.remove(runId)) error("status request failed")
                    val shouldBlock = blockNextStatus.also { blockNextStatus = false }
                    val status = if (statuses.isEmpty()) error("missing status") else statuses.removeFirst()
                    shouldBlock to status
                }
            if (shouldBlock) {
                statusStarted.countDown()
                try {
                    check(releaseStatus.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        "Status was not released."
                    }
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
            val shouldBlockOpen =
                synchronized(this) {
                    observationRequests += runId
                    if (observationRequests.size == 2) {
                        secondObservationRequested.countDown()
                    }
                    blockNextObservationOpen.also { blockNextObservationOpen = false }
                }
            if (shouldBlockOpen) {
                observationOpenStarted.countDown()
                try {
                    while (true) {
                        try {
                            if (releaseObservationOpen.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) break
                        } catch (_: InterruptedException) {
                        }
                    }
                } finally {
                    observationOpenFinished.countDown()
                }
            }
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
                        error("stream interrupted")
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
                        check(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                            "Observation was not released."
                        }
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

    private class BlockingContainsMap<K, V>(
        private val delegate: MutableMap<K, V>,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : AbstractMutableMap<K, V>() {
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = delegate.entries

        override fun put(
            key: K,
            value: V,
        ): V? = delegate.put(key, value)

        override fun containsKey(key: K): Boolean {
            entered.countDown()
            check(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Blocked map lookup was not released."
            }
            return delegate.containsKey(key)
        }
    }

    private class BlockingRecoveryRegistry : RunRecoveryRegistry {
        private val entries = mutableListOf<RunRecoveryEntry>()
        val loadStarted = CountDownLatch(1)
        val loadFinished = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        @Volatile
        var blockLoads = false

        @Volatile
        var blockSaves = false

        @Volatile
        var blockRemovals = false

        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val removeRequests = AtomicInteger(0)
        val releaseRemove = CountDownLatch(1)

        override fun load(): List<RunRecoveryEntry> {
            val shouldSignalCompletion = blockLoads
            if (shouldSignalCompletion) {
                loadStarted.countDown()
                check(releaseLoad.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Recovery load was not released."
                }
            }
            return synchronized(entries) { entries.toList() }.also {
                if (shouldSignalCompletion) loadFinished.countDown()
            }
        }

        override fun save(entry: RunRecoveryEntry) {
            if (blockSaves) {
                saveStarted.countDown()
                check(releaseSave.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Recovery save was not released."
                }
            }
            synchronized(entries) {
                if (entry !in entries) entries += entry
            }
        }

        override fun remove(entry: RunRecoveryEntry) {
            removeRequests.incrementAndGet()
            if (blockRemovals) {
                check(releaseRemove.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "Recovery removal was not released."
                }
            }
            synchronized(entries) { entries -= entry }
        }
    }

    private fun privateField(
        holder: EntryStateHolder,
        name: String,
        replacement: Any? = null,
    ): Any {
        val field = EntryStateHolder::class.java.getDeclaredField(name).apply { isAccessible = true }
        if (replacement != null) field.set(holder, replacement)
        return field.get(holder)
    }

    private fun invokeHistoryRunIdToReconcile(
        holder: EntryStateHolder,
        sessionId: SessionId,
        openedSession: org.hermesnative.client.feature.entry.application.OpenedSession,
    ): RunId? {
        val method =
            EntryStateHolder::class.java.declaredMethods.single { it.name.startsWith("historyRunIdToReconcile") }
                .apply { isAccessible = true }
        return (method.invoke(holder, sessionId.value, openedSession) as String?)?.let(::RunId)
    }

    private class InMemoryUncertaintyStorage : RunSubmissionUncertaintyStorage {
        private val records = mutableMapOf<PendingRunSubmissionKey, RunSubmissionUncertaintySnapshot>()

        override fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? = records[key]

        override fun write(
            key: PendingRunSubmissionKey,
            snapshot: RunSubmissionUncertaintySnapshot,
        ) {
            records[key] = snapshot
        }

        override fun remove(key: PendingRunSubmissionKey) {
            records.remove(key)
        }
    }

    private class PausingDispatcher(
        private val delegate: CoroutineDispatcher? = null,
    ) : CoroutineDispatcher() {
        @Volatile
        var paused = false

        private val queued = ArrayDeque<Runnable>()
        val queuedCount: Int get() = synchronized(queued) { queued.size }

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            if (paused) {
                synchronized(queued) { queued.addLast(block) }
            } else if (delegate != null) {
                delegate.dispatch(context, block)
            } else {
                block.run()
            }
        }

        fun runNext() = synchronized(queued) { queued.removeFirst() }.run()

        fun runLast() = synchronized(queued) { queued.removeLast() }.run()

        fun drain() {
            repeat(100) {
                if (queuedCount == 0) return
                runNext()
            }
            error("Dispatcher did not become idle.")
        }
    }

    private class DelayedUnwindObservation : RunEventObservation {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val closeCount = AtomicInteger(0)

        override fun iterator(): Iterator<RunEvent> {
            started.countDown()
            return object : Iterator<RunEvent> {
                override fun hasNext(): Boolean {
                    return try {
                        awaitRelease()
                        false
                    } catch (error: InterruptedException) {
                        Thread.interrupted()
                        hasNext()
                    } finally {
                        finished.countDown()
                    }
                }

                override fun next(): RunEvent = error("not used")
            }
        }

        override fun close() {
            if (closeCount.incrementAndGet() == 1) {
                closed.countDown()
            }
        }

        private fun awaitRelease() {
            var released = release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            while (!released) {
                released = release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
        const val SETTLE_MILLIS = 500L
    }
}
