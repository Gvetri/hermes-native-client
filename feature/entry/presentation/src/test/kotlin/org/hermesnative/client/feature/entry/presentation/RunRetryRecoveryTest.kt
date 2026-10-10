package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunRetryRecoveryTest {
    @Test
    fun retry_after_an_active_run_fails_through_sse_and_reconciles_with_pinned_history_creates_exactly_one_new_run() {
        val session = retrySession("session-1")
        val failedRunId = RunId("run-retry-1")
        val retryRunId = RunId("run-retry-2")
        val gateway = activeRunRetryGateway(session, failedRunId, retryRunId)
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            gateway.setHistory(
                retryHistory(
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
            awaitFailedRunSettled(holder, failedRunId)

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)
            assertEquals(listOf(session.id to "Retry this"), gateway.runRequests)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) {
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
    fun retry_after_sse_failure_with_the_persistent_submission_store_and_recovery_registry_creates_one_new_run() {
        val session = retrySession("session-1")
        val failedRunId = RunId("run-retry-1")
        val retryRunId = RunId("run-retry-2")
        val gateway = activeRunRetryGateway(session, failedRunId, retryRunId)
        val registry = InMemoryRunRecoveryRegistry()
        val holder = persistentRetryHolder(gateway, registry)

        try {
            openRetrySession(holder, gateway, session.id)
            gateway.setHistory(
                retryHistory(
                    session.id,
                    GatewayHistoryMessage("message-retry-failed", "assistant", "Stable retry failure"),
                ),
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Retry this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitFailedRunSettled(holder, failedRunId)

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRunId }

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
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val gateway =
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to retryHistory(session.id)),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            )
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(RunRecoveryEntry(session.id, failedRunId)))
        val holder =
            retryHolder(
                gateway,
                settings =
                    RetryHolderSettings(
                        recoveryRegistry = recoveryRegistry,
                    ),
            )

        try {
            openRetrySession(holder, gateway, session.id)
            awaitRetryState(holder) {
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
        val session = retrySession("session-1")
        val runId = RunId("run-shared")
        val gateway =
            RetryFakeGateway(sessions = listOf(session)).apply {
                enqueueRun(Run(runId, session.id, "running"))
                blockRunCreation = true
            }
        val holder =
            retryHolder(
                gateway,
                settings =
                    RetryHolderSettings(
                        dispatcher = Dispatchers.Default,
                        onRunSubmissionSettled = gateway.runFinished::countDown,
                    ),
            )

        try {
            connectRetryGateway(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitRetryState(holder) { it.sessionList?.openedSession?.session?.id == session.id }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("OLD SECRET"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(RETRY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            gateway.releaseRun.countDown()
            assertTrue(gateway.runFinished.await(RETRY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.setHistory(
                retryHistory(
                    session.id,
                    retryUserMessage("NEW MESSAGE", runId),
                    retryFailedMessage(runId, "Remote failure"),
                ),
            )
            gateway.setRunStatus(Run(runId, session.id, "failed"))
            connectRetryGateway(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitRetryState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.messages?.any { m -> m.runId == runId && m.isFailedRun } == true &&
                    opened.isRefreshing == false &&
                    opened.isReconciliationInProgress == false
            }

            val failedMessage =
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).messages.single {
                    it.runId == runId && it.isFailedRun
                }
            assertTrue(failedMessage.retryAvailable)
            holder.onEvent(EntryUiEvent.RetryRunClicked(runId))
            awaitRetryState(holder) {
                it.sessionList?.openedSession?.latestRun?.id == runId && gateway.runRequests.size == 2
            }

            assertEquals(listOf(session.id to "OLD SECRET", session.id to "NEW MESSAGE"), gateway.runRequests)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    private fun activeRunRetryGateway(
        session: Session,
        failedRunId: RunId,
        retryRunId: RunId,
    ): RetryFakeGateway =
        RetryFakeGateway(
            sessions = listOf(session),
            runStatuses =
                mapOf(
                    failedRunId to Run(failedRunId, session.id, "failed"),
                    retryRunId to Run(retryRunId, session.id, "completed"),
                ),
        ).apply {
            enqueueRun(Run(failedRunId, session.id, "started"))
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

    private fun awaitFailedRunSettled(
        holder: EntryStateHolder,
        failedRunId: RunId,
    ) {
        awaitRetryState(holder) {
            val opened = it.sessionList?.openedSession
            opened?.latestRun?.id == failedRunId &&
                opened.latestRunState == RunPresentationState.FAILED &&
                !opened.isRefreshing &&
                !opened.isReconciliationInProgress
        }
    }
}
