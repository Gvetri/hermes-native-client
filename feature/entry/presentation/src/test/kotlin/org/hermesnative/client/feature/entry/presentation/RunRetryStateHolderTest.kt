package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRetryStateHolderTest {
    @Test
    fun retry_creates_a_new_run_with_the_original_message_and_preserves_the_failed_run_identity() {
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            retryHistory(
                session.id,
                retryUserMessage("Original request", failedRunId),
                retryFailedMessage(failedRunId, "Remote failure"),
            )
        val retryRun = Run(RunId("run-retried"), session.id, "running")
        val gateway =
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(retryRun) }
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            val openedBefore = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            val failedMessageBefore = openedBefore.messages.single { it.runId == failedRunId && it.isFailedRun }
            assertTrue(failedMessageBefore.isFailedRun)
            assertTrue(failedMessageBefore.retryAvailable)
            assertNull(failedMessageBefore.failureTechnicalDetail)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRun.id }

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
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            retryHistory(
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
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(Run(failedRunId, session.id, "failed")) }
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Original request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitRetryState(
                holder,
            ) {
                it.sessionList?.openedSession?.latestRun?.id == failedRunId &&
                    !it.sessionList!!.openedSession!!.isSending
            }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Edited draft"))
            val retryRun = Run(RunId("run-retried"), session.id, "succeeded")
            gateway.enqueueRun(retryRun)
            gateway.setHistory(retryCompletionHistory(session, failedRunId, retryRun))
            gateway.setRunStatus(retryRun)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRun.id }

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(
                listOf(session.id to "Original request", session.id to "Original request"),
                gateway.runRequests,
            )
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
        val session = retrySession("session-1")
        val externalFailedRunId = RunId("external-run-failed")
        val history =
            retryHistory(
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
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(externalFailedRunId to Run(externalFailedRunId, session.id, "failed")),
            )
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            val failedMessage = opened.messages.single { it.runId == externalFailedRunId }
            assertTrue(failedMessage.isFailedRun)
            assertFalse(failedMessage.retryAvailable)
            assertEquals(RunFailureCategory.GATEWAY_REPORTED.safeMessage, failedMessage.failureSafeMessage)

            holder.onEvent(EntryUiEvent.RetryRunClicked(externalFailedRunId))

            assertTrue(gateway.runRequests.isEmpty())
            assertEquals(
                externalFailedRunId,
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).latestRun?.id,
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_is_not_available_for_uncertain_or_succeeded_runs() {
        val uncertainSession = retrySession("session-1")
        val succeededSession = retrySession("session-2")
        val uncertainHistory = statusHistory(uncertainSession.id, "run-uncertain", "Interrupted request", "interrupted")
        val succeededHistory = statusHistory(succeededSession.id, "run-done", "Done request", "succeeded", "Done")
        val gateway =
            RetryFakeGateway(
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
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, uncertainSession.id)
            val uncertainMessage =
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).messages.single {
                    it.runId?.value == "run-uncertain" && it.runStatus == "interrupted"
                }
            assertFalse(uncertainMessage.retryAvailable)
            assertNull(uncertainMessage.failureSafeMessage)
            assertNull(uncertainMessage.failureTechnicalDetail)

            holder.onEvent(EntryUiEvent.RetryRunClicked(RunId("run-uncertain")))
            assertTrue(gateway.runRequests.isEmpty())

            openRetrySession(holder, gateway, succeededSession.id)
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
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            retryHistory(
                session.id,
                retryUserMessage("Original request", failedRunId),
                retryFailedMessage(failedRunId, "Remote failure"),
            )
        val gateway =
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRun(Run(failedRunId, session.id, "failed")) }
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }

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
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val retryRunId = RunId("run-retried")
        val gateway =
            RetryFakeGateway(
                sessions = listOf(session),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply {
                enqueueRun(Run(failedRunId, session.id, "failed"))
                enqueueRun(Run(retryRunId, session.id, "running"))
            }
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            gateway.setHistory(
                retryHistory(
                    session.id,
                    GatewayHistoryMessage(id = "message-failed", role = null, content = null),
                ),
            )
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Original request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitRetryState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == failedRunId &&
                    opened.latestRunState == RunPresentationState.FAILED
            }

            val settled = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(settled.latestRunRetryAvailable)
            assertTrue(settled.messages.none { message -> message.retryAvailable })

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) { it.sessionList?.openedSession?.latestRun?.id == retryRunId }

            assertEquals(
                listOf(session.id to "Original request", session.id to "Original request"),
                gateway.runRequests,
            )
            val retried = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertFalse(retried.latestRunRetryAvailable)

            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            assertEquals(2, gateway.runRequests.size)
        } finally {
            holder.close()
        }
    }

    @Test
    fun retry_failure_surfaces_a_safe_send_error_and_keeps_the_failed_run_retryable() {
        val session = retrySession("session-1")
        val failedRunId = RunId("run-failed")
        val history =
            retryHistory(
                session.id,
                retryUserMessage("Original request", failedRunId),
                retryFailedMessage(failedRunId, "Remote failure"),
            )
        val gateway =
            RetryFakeGateway(
                sessions = listOf(session),
                histories = mapOf(session.id to history),
                runStatuses = mapOf(failedRunId to Run(failedRunId, session.id, "failed")),
            ).apply { enqueueRunFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)) }
        val holder = retryHolder(gateway)

        try {
            openRetrySession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RetryRunClicked(failedRunId))
            awaitRetryState(holder) {
                it.sessionList?.openedSession?.sendErrorCategory == MessageSendErrorCategory.GATEWAY_REQUEST_FAILED
            }

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

    private fun retryCompletionHistory(
        session: Session,
        failedRunId: RunId,
        retryRun: Run,
    ): SessionHistory =
        retryHistory(
            session.id,
            retryUserMessage("Original request", failedRunId),
            retryFailedMessage(failedRunId, "Remote failure"),
            retryUserMessage("Original request", retryRun.id),
            GatewayHistoryMessage(
                id = "retry-done",
                role = null,
                content = null,
                runId = retryRun.id,
                runStatus = "succeeded",
                runResult = "Done",
            ),
        )

    private fun statusHistory(
        sessionId: SessionId,
        runId: String,
        request: String,
        status: String,
        result: String? = null,
    ): SessionHistory =
        retryHistory(
            sessionId,
            retryUserMessage(request, RunId(runId)),
            GatewayHistoryMessage(
                id = "$runId-message",
                role = null,
                content = null,
                runId = RunId(runId),
                runStatus = status,
                runResult = result,
            ),
        )
}
