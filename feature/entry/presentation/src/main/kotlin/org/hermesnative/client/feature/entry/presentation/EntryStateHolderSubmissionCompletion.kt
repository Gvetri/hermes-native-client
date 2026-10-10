package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.SubmitMessage
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive

internal fun EntryStateHolder.abortUnclaimedRunJob(submission: PreparedRunSubmission) {
    val persistence = submission.persistence
    synchronized(sessionRequestLock) {
        if (
            pendingCreateSessions[persistence.recoveryKey] ==
            PendingCreateState(persistence.knownRunIds, persistence.attemptId)
        ) {
            pendingCreateSessions.remove(persistence.recoveryKey)
            pendingCreateStarted.remove(persistence.recoveryKey)
            unresolvedSubmissionSessions += persistence.key.sessionId
            pendingRunDrafts.remove(persistence.key.sessionId)?.let { draft ->
                uncertainSendDrafts[persistence.key.sessionId] = draft
            }
            sessionSendErrors[persistence.key.sessionId] = MessageSendErrorCategory.UNCERTAIN
            val (current, opened) = openedSessionFor(persistence.key.sessionId) ?: return@synchronized
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    isSending = false,
                                    sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                    hasUnresolvedSubmission = true,
                                ),
                        ),
                )
        }
    }
    synchronized(sessionRequestLock) {
        if (runJobs[persistence.key.sessionId] === submission.job) {
            runJobs.remove(persistence.key.sessionId)
        }
    }
    submission.job.cancel()
}

internal suspend fun EntryStateHolder.submitRun(work: RunSubmissionWork) {
    if (
        !markPendingCreateStarted(
            work.recoverySessionKey,
            work.pendingCreate,
            work.requestConnectionGeneration,
        )
    ) {
        return
    }
    try {
        val run =
            withContext(NonCancellable) {
                withTimeout(sendTimeoutMillis) {
                    runInterruptible {
                        SubmitMessage(work.gateway).execute(work.sessionId, work.input)
                    }
                }
            }
        val applied = completeSuccessfulSubmission(work, run)
        if (applied) {
            if (!run.isActive()) {
                reconcileInactiveSubmittedRun(work.sessionId, run.id, work.requestConnectionGeneration)
            }
            onRunSubmissionCompleted?.invoke()
        }
    } catch (_: TimeoutCancellationException) {
        if (reportUncertainRunSubmission(work)) {
            onRunSubmissionCompleted?.invoke()
        }
    } catch (error: CancellationException) {
        runCatching {
            runSubmissionUncertaintyStore.markAmbiguous(
                PendingRunSubmissionKey(work.requestEndpoint, work.sessionId),
                work.attemptId,
            )
        }
        throw error
    } catch (_: GatewayException) {
        if (reportUncertainRunSubmission(work, MessageSendErrorCategory.GATEWAY_REQUEST_FAILED)) {
            onRunSubmissionCompleted?.invoke()
        }
    } catch (_: Exception) {
        if (reportUncertainRunSubmission(work, MessageSendErrorCategory.GATEWAY_REQUEST_FAILED)) {
            onRunSubmissionCompleted?.invoke()
        }
    } finally {
        forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
        onRunSubmissionSettled?.invoke()
    }
}

internal fun EntryStateHolder.completeSuccessfulSubmission(
    work: RunSubmissionWork,
    run: Run,
): Boolean {
    if (work.rejectRunId != null && run.id == work.rejectRunId) {
        throw GatewayException(
            GatewayErrorCategory.INVALID_RESPONSE,
            "Retry response reused the failed Run identity.",
        )
    }
    synchronized(sessionRequestLock) {
        if (connectionGeneration == work.requestConnectionGeneration) {
            submittedRunInputs[run.id] = work.input
        }
    }
    val applied =
        applySubmittedRun(
            sessionId = work.sessionId,
            run = run,
            requestConnectionGeneration = work.requestConnectionGeneration,
            requestEndpoint = work.requestEndpoint,
            attemptId = work.attemptId,
        )
    forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
    recordDiagnostic(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.SUCCEEDED)
    return applied
}

internal suspend fun EntryStateHolder.reportUncertainRunSubmission(
    work: RunSubmissionWork,
    uncertaintyErrorCategory: MessageSendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
): Boolean {
    recordDiagnostic(LocalDiagnosticEventType.RUN_SUBMISSION, LocalDiagnosticStatus.UNCERTAIN)
    runCatching {
        runSubmissionUncertaintyStore.markAmbiguous(
            PendingRunSubmissionKey(work.requestEndpoint, work.sessionId),
            work.attemptId,
        )
    }
    forgetPendingCreate(work.recoverySessionKey, work.pendingCreate)
    return reconcileTimedOutSend(
        TimedOutSendReconciliationRequest(
            sessionId = work.sessionId,
            connectionGeneration = work.requestConnectionGeneration,
            knownRunIds = work.knownRunIds,
            attemptId = work.attemptId,
            uncertaintyErrorCategory = uncertaintyErrorCategory,
        ),
    )
}

internal fun EntryStateHolder.reconcileInactiveSubmittedRun(
    sessionId: SessionId,
    runId: RunId,
    requestConnectionGeneration: Long,
) {
    val reconciliationContext =
        synchronized(sessionRequestLock) {
            sessionGateway
                ?.takeIf {
                    connectionGeneration == requestConnectionGeneration &&
                        mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
                }?.let { it to sessionRequestGeneration }
        }
    if (reconciliationContext != null) {
        reconcileRun(
            sessionId = sessionId,
            runId = runId,
            requestConnectionGeneration = requestConnectionGeneration,
            requestSessionGeneration = reconciliationContext.second,
            sessionGateway = reconciliationContext.first,
        )
    }
}

internal fun EntryStateHolder.prepareSettledSubmissionRecovery(sessionId: SessionId): TimedOutSendRecovery? {
    return synchronized(sessionRequestLock) {
        val key = pendingRunSubmissionKey(sessionId)
        if (
            !runSubmissionUncertaintyStore.contains(key) ||
            !runSubmissionUncertaintyStore.isSettled(key) ||
            pendingTimedOutSends.containsKey(sessionId)
        ) {
            null
        } else {
            TimedOutSendRecovery(
                knownRunIds = runSubmissionUncertaintyStore.knownRunIds(key),
                attemptId = runSubmissionUncertaintyStore.attemptId(key) ?: LEGACY_ATTEMPT_ID,
                draft =
                    PendingDraft(
                        text = sessionDrafts[sessionId].orEmpty(),
                        revision = sessionDraftRevisions[sessionId] ?: 0L,
                    ),
            ).also { pendingTimedOutSends[sessionId] = it }
        }
    }
}
