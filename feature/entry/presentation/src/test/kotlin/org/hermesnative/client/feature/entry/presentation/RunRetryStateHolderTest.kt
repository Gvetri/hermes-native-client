package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.DefaultRunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.data.RunSubmissionUncertaintyStorage
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TEST_TIMEOUT_MILLIS = 5_000L

class RunRetryStateHolderTest {
    @Test
    fun retry_creates_a_new_run_with_the_original_message_and_preserves_the_failed_run_identity() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            history(
                session.id,
                userMessage("Original request", failedRunId),
                failedMessage(failedRunId, "Remote failure"),
            )
        val retryRun = Run(RunId("run-retried"), session.id, "running")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(retryRun) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            val openedBefore = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            val failedMessageBefore = openedBefore.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertTrue(failedMessageBefore.isFailedRun)
            assertTrue(failedMessageBefore.retryAvailable)
            assertNull(failedMessageBefore.failureTechnicalDetail)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRun.id }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(session.id to "Original request"), gateway.runRequests)
            assertEquals(retryRun, opened.latestRun)
            assertFalse(opened.isSending)
            assertFalse(opened.hasUnresolvedSubmission)

            val failedMessageAfter = opened.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertEquals(failedMessageBefore.isFailedRun, failedMessageAfter.isFailedRun)
            assertEquals(failedMessageBefore.runStatus, failedMessageAfter.runStatus)
            assertEquals(failedMessageBefore.failureSafeMessage, failedMessageAfter.failureSafeMessage)
            assertNull(failedMessageAfter.failureTechnicalDetail)
            assertTrue(failedMessageAfter.retryAvailable)
            assertEquals(RunId("run-retried"), opened.activeResponse?.runId)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_uses_the_recorded_original_message_and_never_overwrites_the_composer_draft() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            history(
                session.id,
                GatewayHistoryMessage(
                    id = "run-message",
                    role = null,
                    content = null,
                    runId = failedRunId,
                    runStatus = "failed",
                    runResult = "Remote failure",
                ),
            )
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(Run(failedRunId, session.id, "failed")) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Original request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(
                holder,
            ) { it.sessionList?.openedSession?.latestRun?.id == failedRunId && !it.sessionList!!.openedSession!!.isSending }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Edited draft"))
            val retryRun = Run(RunId("run-retried"), session.id, "succeeded")
            gateway.enqueueRun(retryRun)
            gateway.setHistory(
                history(
                    session.id,
                    userMessage("Original request", failedRunId),
                    failedMessage(failedRunId, "Remote failure"),
                    userMessage("Original request", retryRun.id),
                    GatewayHistoryMessage(
                        id = "retry-done",
                        role = null,
                        content = null,
                        runId = retryRun.id,
                        runStatus = "succeeded",
                        runResult = "Done",
                    ),
                ),
            )
            gateway.setRunStatus(retryRun)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRun.id }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(session.id to "Original request", session.id to "Original request"), gateway.runRequests)
            assertEquals("Edited draft", opened.composerText)
            val failedMessage = opened.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertTrue(failedMessage.isFailedRun)
            assertEquals("failed", failedMessage.runStatus)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_is_not_available_for_a_failed_run_without_an_original_message() {
        val session = session("session-1")
        val externalFailedRunId = RunId("external-run-failed")
        val history =
            history(
                session.id,
                GatewayHistoryMessage(
                    id = "external-failed",
                    role = null,
                    content = null,
                    runId = externalFailedRunId,
                    runStatus = "failed",
                    runResult = "Remote failure",
                ),
            )
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(externalFailedRunId to Run(externalFailedRunId, session.id, "failed")),
            )
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            val failedMessage = opened.messages.single { it.runId == externalFailedRunId }
            assertTrue(failedMessage.isFailedRun)
            assertFalse(failedMessage.retryAvailable)
            assertEquals(RunFailureCategory.GATEWAY_REPORTED.safeMessage, failedMessage.failureSafeMessage)

            holder.onEvent(EntryUiEvent.RetryRunClicked(externalFailedRunId))

            assertTrue(gateway.runRequests.isEmpty())
            assertEquals(externalFailedRunId, requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).latestRun?.id)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_is_not_available_for_uncertain_or_succeeded_runs() {
        val uncertainSession = session("session-1")
        val succeededSession = session("session-2")
        val uncertainHistory =
            history(
                uncertainSession.id,
                userMessage("Interrupted request", RunId("run-uncertain")),
                GatewayHistoryMessage(
                    id = "uncertain",
                    role = null,
                    content = null,
                    runId = RunId("run-uncertain"),
                    runStatus = "interrupted",
                ),
            )
        val succeededHistory =
            history(
                succeededSession.id,
                userMessage("Done request", RunId("run-done")),
                GatewayHistoryMessage(
                    id = "done",
                    role = null,
                    content = null,
                    runId = RunId("run-done"),
                    runStatus = "succeeded",
                    runResult = "Done",
                ),
            )
        val gateway =
            FakeGateway(
                sessions = listOf(uncertainSession, succeededSession),
                histories =
                    mapOf(
                        uncertainSession.id to uncertainHistory,
                        succeededSession.id to succeededHistory,
                    ),
                runStatuses =
                    mapOf(
                        RunId("run-uncertain") to Run(RunId("run-uncertain"), uncertainSession.id, "interrupted"),
                        RunId("run-done") to Run(RunId("run-done"), succeededSession.id, "succeeded"),
                    ),
            )
        val holder = holder(gateway)

        try {
            open(holder, gateway, uncertainSession.id)
            val uncertainMessage =
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).messages.single {
                    it.runId?.value == "run-uncertain" && it.runStatus == "interrupted"
                }
            assertFalse(uncertainMessage.retryAvailable)
            assertNull(uncertainMessage.failureSafeMessage)
            assertNull(uncertainMessage.failureTechnicalDetail)

            holder.onEvent(EntryUiEvent.RetryRunClicked(RunId("run-uncertain")))
            assertTrue(gateway.runRequests.isEmpty())

            open(holder, gateway, succeededSession.id)
            val succeededMessage =
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).messages.single {
                    it.runId?.value == "run-done" && it.runStatus == "succeeded"
                }
            assertFalse(succeededMessage.retryAvailable)
            assertNull(succeededMessage.failureSafeMessage)

            holder.onEvent(EntryUiEvent.RetryRunClicked(RunId("run-done")))
            assertTrue(gateway.runRequests.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_rejects_a_response_that_reuses_the_failed_run_identity() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            history(
                session.id,
                userMessage("Original request", failedRunId),
                failedMessage(failedRunId, "Remote failure"),
            )
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(Run(failedRunId, session.id, "failed")) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) { it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(session.id to "Original request"), gateway.runRequests)
            assertEquals(failedRunId, opened.latestRun?.id)
            val failedMessage = opened.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertTrue(failedMessage.isFailedRun)
            assertEquals("failed", failedMessage.runStatus)
            assertTrue(failedMessage.retryAvailable)
            assertEquals(RunFailureCategory.GATEWAY_REPORTED.safeMessage, failedMessage.failureSafeMessage)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_is_available_for_a_locally_known_failed_run_when_pinned_history_has_no_run_linkage() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val retryRunId = RunId("run-retried")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply {
                enqueueRun(Run(failedRunId, session.id, "failed"))
                enqueueRun(Run(retryRunId, session.id, "running"))
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            // Pin-faithful history: no message carries Run linkage, so only the
            // client's own record of the Run and its original input can expose
            // the retry.
            gateway.setHistory(
                history(
                    session.id,
                    GatewayHistoryMessage(id = "message-failed", role = null, content = null),
                ),
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Original request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == failedRunId &&
                    opened.latestRunState == RunPresentationState.FAILED
            }

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)
            assertTrue(settled.messages.none { message -> message.retryAvailable })

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRunId }

            assertEquals(
                listOf(session.id to "Original request", session.id to "Original request"),
                gateway.runRequests,
            )
            val retried = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertFalse(retried.latestRunRetryAvailable)

            // A duplicate tap while the retry Run is active must not create another Run.
            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            assertEquals(2, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_after_an_active_run_fails_through_sse_and_reconciles_with_pinned_history_creates_exactly_one_new_run() {
        val session = session("session-1")
        val failedRunId = RunId("run-retry-1")
        val retryRunId = RunId("run-retry-2")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                runStatuses =
                    mapOf(
                        failedRunId to Run(failedRunId, session.id, "failed"),
                        retryRunId to Run(retryRunId, session.id, "completed"),
                    ),
            ).apply {
                // The admission starts the Run active; the pinned Run stream then reports
                // the failure with no status field (`run.failed` carries only the event
                // type and the Run identity), and the authoritative status read settles it.
                enqueueRun(Run(failedRunId, session.id, "started"))
                // The pinned frame carries no status field; the wire parser supplies the
                // terminal default (`run.failed` -> "failed", `run.completed` -> "succeeded").
                enqueueObservation(
                    failedRunId,
                    RunEvent(type = RunEventType.FAILED, runId = failedRunId, status = "failed"),
                )
                enqueueRun(Run(retryRunId, session.id, "started"))
                enqueueObservation(
                    retryRunId,
                    RunEvent(type = RunEventType.COMPLETED, runId = retryRunId, status = "succeeded"),
                )
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            // Pin-faithful terminal history: the message carries no Run linkage, so the
            // retry must come from the client's own record of the Run and its input.
            gateway.setHistory(
                history(
                    session.id,
                    GatewayHistoryMessage(
                        id = "message-retry-failed",
                        role = "assistant",
                        content = "Stable retry failure",
                    ),
                ),
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Retry this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == failedRunId &&
                    opened.latestRunState == RunPresentationState.FAILED &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress
            }

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)
            assertEquals(listOf(session.id to "Retry this"), gateway.runRequests)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == retryRunId && opened.latestRunState == RunPresentationState.SUCCEEDED
            }

            assertEquals(
                listOf(session.id to "Retry this", session.id to "Retry this"),
                gateway.runRequests,
            )
            val retried = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertFalse(retried.hasUnresolvedSubmission)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_after_sse_failure_with_the_persistent_submission_store_and_recovery_registry_creates_exactly_one_new_run() {
        val session = session("session-1")
        val failedRunId = RunId("run-retry-1")
        val retryRunId = RunId("run-retry-2")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                runStatuses =
                    mapOf(
                        failedRunId to Run(failedRunId, session.id, "failed"),
                        retryRunId to Run(retryRunId, session.id, "completed"),
                    ),
            ).apply {
                enqueueRun(Run(failedRunId, session.id, "started"))
                // The pinned frame carries no status field; the wire parser supplies the
                // terminal default (`run.failed` -> "failed", `run.completed` -> "succeeded").
                enqueueObservation(
                    failedRunId,
                    RunEvent(type = RunEventType.FAILED, runId = failedRunId, status = "failed"),
                )
                enqueueRun(Run(retryRunId, session.id, "started"))
                enqueueObservation(
                    retryRunId,
                    RunEvent(type = RunEventType.COMPLETED, runId = retryRunId, status = "succeeded"),
                )
            }
        val registry = InMemoryRunRecoveryRegistry()
        val holder =
            holder(
                gateway,
                dispatcher = Dispatchers.Default,
                recoveryRegistry = registry,
                uncertaintyStore = DefaultRunSubmissionUncertaintyStore(InMemoryUncertaintyStorage()),
                persistRunRecoveryEntry = { _, entry -> registry.save(entry) },
                removeRunRecoveryEntry = { _, entry -> registry.remove(entry) },
            )

        try {
            open(holder, gateway, session.id)
            gateway.setHistory(
                history(
                    session.id,
                    GatewayHistoryMessage(
                        id = "message-retry-failed",
                        role = "assistant",
                        content = "Stable retry failure",
                    ),
                ),
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Retry this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == failedRunId &&
                    opened.latestRunState == RunPresentationState.FAILED &&
                    !opened.isRefreshing &&
                    !opened.isReconciliationInProgress
            }

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            runBlocking {
                withTimeoutOrNull(TEST_TIMEOUT_MILLIS) {
                    holder.uiState.first { it.sessionList?.openedSession?.latestRun?.id == retryRunId }
                }
            }

            assertEquals(
                listOf(session.id to "Retry this", session.id to "Retry this"),
                gateway.runRequests,
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_is_not_available_for_a_recovered_failed_run_without_the_original_input() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history(session.id)),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            )
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(RunRecoveryEntry(session.id, failedRunId)))
        val holder = holder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            open(holder, gateway, session.id)
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == failedRunId &&
                    opened.latestRunState == RunPresentationState.FAILED
            }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertFalse(opened.latestRunRetryAvailable)
            assertTrue(opened.messages.none { message -> message.retryAvailable })

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))

            assertTrue(gateway.runRequests.isEmpty())
            assertEquals(
                failedRunId,
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).latestRun?.id,
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun late_inflight_response_does_not_repopulate_submitted_inputs_after_connection_removal() {
        val session = session("session-1")
        val runId = RunId("run-shared")
        val gateway =
            FakeGateway(sessions = listOf(session)).apply {
                enqueueRun(Run(runId, session.id, "running"))
                blockRunCreation = true
            }
        val holder = holder(gateway, dispatcher = Dispatchers.Default, onRunSubmissionSettled = gateway.runFinished::countDown)

        try {
            connect(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) { it.sessionList?.openedSession?.session?.id == session.id }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("OLD SECRET"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            gateway.releaseRun.countDown()
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.setHistory(
                history(
                    session.id,
                    userMessage("NEW MESSAGE", runId),
                    failedMessage(runId, "Remote failure"),
                ),
            )
            gateway.setRunStatus(Run(runId, session.id, "failed"))
            connect(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.messages?.any { m -> m.runId == runId && m.isFailedRun } == true &&
                    opened?.isRefreshing == false &&
                    opened?.isReconciliationInProgress == false
            }

            val failedMessage =
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).messages.single {
                    it.runId == runId && it.isFailedRun
                }
            assertTrue(failedMessage.retryAvailable)
            holder.onEvent(EntryUiEvent.RetryRunClicked(runId))
            awaitState(holder) { it.sessionList?.openedSession?.latestRun?.id == runId && gateway.runRequests.size == 2 }

            assertEquals(listOf(session.id to "OLD SECRET", session.id to "NEW MESSAGE"), gateway.runRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun retry_failure_surfaces_a_safe_send_error_and_keeps_the_failed_run_retryable() {
        val session = session("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            history(
                session.id,
                userMessage("Original request", failedRunId),
                failedMessage(failedRunId, "Remote failure"),
            )
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRunFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitState(holder) { it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertFalse(opened.isSending)
            assertEquals(MessageSendErrorCategory.GATEWAY_REQUEST_FAILED, opened.sendErrorCategory)
            val failedMessage = opened.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertTrue(failedMessage.isFailedRun)
            assertTrue(failedMessage.retryAvailable)
            assertEquals(RunFailureCategory.GATEWAY_REPORTED.safeMessage, failedMessage.failureSafeMessage)
        } finally {
            holder.close()
        }
    }

    private fun holder(
        gateway: FakeGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        onRunSubmissionSettled: (() -> Unit)? = null,
        recoveryRegistry: RunRecoveryRegistry? = null,
        uncertaintyStore: RunSubmissionUncertaintyStore = NoOpRunSubmissionUncertaintyStore,
        persistRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
        removeRunRecoveryEntry: ((String, RunRecoveryEntry) -> Unit)? = null,
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredEndpoints)
                },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            runRecoveryRegistry = recoveryRegistry,
            removeGatewayConnectionUseCase =
                RemoveGatewayConnection(
                    DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
                ),
            runSubmissionUncertaintyStore = uncertaintyStore,
            persistRunRecoveryEntry = persistRunRecoveryEntry,
            removeRunRecoveryEntry = removeRunRecoveryEntry,
            onRunSubmissionSettled = onRunSubmissionSettled,
        )

    private fun connect(
        holder: EntryStateHolder,
        gateway: FakeGateway,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) {
            it.sessionList?.sessions ==
                gateway.sessions.map { session -> session.toSessionItemUiState() }
        }
    }

    private fun open(
        holder: EntryStateHolder,
        gateway: FakeGateway,
        sessionId: SessionId,
    ) {
        connect(holder, gateway)
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
        )

    private fun history(
        sessionId: SessionId,
        vararg messages: GatewayHistoryMessage,
    ): SessionHistory = SessionHistory(sessionId, messages.toList())

    private fun userMessage(
        content: String,
        runId: RunId,
    ): GatewayHistoryMessage = GatewayHistoryMessage(id = "user:$runId.value", role = "user", content = content, runId = runId)

    private fun failedMessage(
        runId: RunId,
        result: String,
    ): GatewayHistoryMessage =
        GatewayHistoryMessage(
            id = "result:$runId.value",
            role = null,
            content = null,
            runId = runId,
            runStatus = "failed",
            runResult = result,
        )

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

    private class FakeGateway(
        val sessions: List<Session>,
        private val histories: Map<SessionId, SessionHistory> = emptyMap(),
        private val runStatuses: Map<RunId, Run> = emptyMap(),
    ) : SessionGatewayPort, RunGatewayPort {
        private val mutableHistories = histories.toMutableMap()
        private val mutableRunStatuses = runStatuses.toMutableMap()
        private val runObservations = mutableMapOf<RunId, List<RunEvent>>()
        val runRequests = mutableListOf<Pair<SessionId, String>>()
        private val runResults = ArrayDeque<Result<Run>>()
        val runStarted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        var blockRunCreation = false

        fun enqueueRun(run: Run) {
            runResults += Result.success(run)
        }

        fun enqueueRunFailure(error: GatewayException) {
            runResults += Result.failure(error)
        }

        fun enqueueObservation(
            runId: RunId,
            vararg events: RunEvent,
        ) {
            runObservations[runId] = events.toList()
        }

        fun setRunStatus(run: Run) {
            mutableRunStatuses[run.id] = run
        }

        fun setHistory(history: SessionHistory) {
            mutableHistories[history.sessionId] = history
        }

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(sessions, null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = sessions.single { it.id == sessionId }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            mutableHistories[sessionId] ?: SessionHistory(sessionId, emptyList())

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
            runRequests += sessionId to input
            if (blockRunCreation) {
                runStarted.countDown()
                check(releaseRun.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "Timed out waiting for Run release." }
            }
            return runResults.removeFirst().getOrThrow()
        }

        override fun getRunStatus(runId: RunId): Run = mutableRunStatuses[runId] ?: error("not used")

        override fun observeRun(runId: RunId): RunEventObservation =
            object : RunEventObservation {
                override fun iterator(): Iterator<RunEvent> = runObservations[runId].orEmpty().iterator()

                override fun close() = Unit
            }
    }
}
