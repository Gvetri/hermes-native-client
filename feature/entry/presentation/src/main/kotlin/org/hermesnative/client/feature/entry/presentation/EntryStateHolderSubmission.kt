package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunSubmissionState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isRunRetryEligible
import org.hermesnative.client.feature.entry.domain.runs
import org.hermesnative.client.feature.entry.domain.toRunPresentationState
import java.util.UUID

internal fun EntryStateHolder.retryUncertainSubmissionOrSend() {
    val retryContext =
        synchronized(sessionRequestLock) {
            val opened = mutableUiState.value.sessionList?.openedSession ?: return@synchronized null
            val sessionId = opened.session.id
            val key = pendingRunSubmissionKey(sessionId)
            if (!opened.hasUnresolvedSubmission ||
                (runSubmissionUncertaintyStore.contains(key) && !runSubmissionUncertaintyStore.isSettled(key))
            ) {
                null
            } else {
                Triple(
                    sessionId,
                    connectionGeneration,
                    runSubmissionUncertaintyStore.knownRunIds(key) to
                        (runSubmissionUncertaintyStore.attemptId(key) ?: LEGACY_ATTEMPT_ID),
                )
            }
        }
    if (retryContext == null) {
        sendMessage()
        return
    }
    val (sessionId, requestConnectionGeneration, recovery) = retryContext
    val (knownRunIds, attemptId) = recovery
    scope.launch {
        val reconciled =
            reconcileTimedOutSend(
                TimedOutSendReconciliationRequest(
                    sessionId = sessionId,
                    connectionGeneration = requestConnectionGeneration,
                    knownRunIds = knownRunIds,
                    attemptId = attemptId,
                    resolveWhenNoNewRun = true,
                ),
            )
        if (reconciled) {
            val canRetry =
                synchronized(sessionRequestLock) {
                    !hasUnresolvedSubmission(sessionId)
                }
            if (canRetry) sendMessage()
        }
    }
}

internal fun EntryStateHolder.sendMessage() {
    val input =
        synchronized(sessionRequestLock) {
            mutableUiState.value.sessionList?.openedSession?.composerText?.takeIf { it.isNotBlank() }
        } ?: return
    launchRunSubmission(input = input, recordDraft = true)
}

internal fun EntryStateHolder.retryRun(runId: RunId) {
    val originalMessage =
        synchronized(sessionRequestLock) {
            val opened = mutableUiState.value.sessionList?.openedSession ?: return@synchronized null
            val sessionId = opened.session.id
            val failedState =
                visibleSessionRuns(sessionId).lastOrNull { it.id == runId }?.toRunPresentationState()
                    ?: observationStateFor(sessionId, runId)?.state
                    ?: opened.messages.lastOrNull { it.runId == runId }?.runState
                    ?: opened.messages
                        .lastOrNull { it.runId == runId && it.isFailedRun }
                        ?.let { RunPresentationState.FAILED }
            val original =
                submittedRunInputs[runId]
                    ?: opened.messages
                        .lastOrNull { it.runId == runId && it.role == "user" }
                        ?.content
                        ?.takeIf { it.isNotBlank() }
            if (!isRunRetryEligible(failedState, original)) return@synchronized null
            original
        } ?: return
    launchRunSubmission(input = originalMessage, recordDraft = false, rejectRunId = runId)
}

internal fun EntryStateHolder.latestRunRetryAvailable(
    runs: List<Run>,
    latestRunState: RunPresentationState?,
): Boolean {
    val latestRun = runs.latestRun() ?: return false
    return isRunRetryEligible(latestRunState, submittedRunInputs[latestRun.id])
}

internal fun EntryStateHolder.launchRunSubmission(
    input: String,
    recordDraft: Boolean,
    rejectRunId: RunId? = null,
) {
    val gateway = runGateway ?: return
    val submission = prepareRunSubmission(gateway, input, recordDraft, rejectRunId) ?: return
    claimAndStartRunJob(submission)
}

internal fun EntryStateHolder.prepareRunSubmission(
    gateway: RunGatewayPort,
    input: String,
    recordDraft: Boolean,
    rejectRunId: RunId?,
): PreparedRunSubmission? =
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return@synchronized null
        val opened = current.openedSession ?: return@synchronized null
        val sessionId = opened.session.id
        val requestConnectionGeneration = connectionGeneration
        val (knownRuns, submissionState) = submissionRunsAndState(opened, sessionId)
        if (!submissionState.canSubmit || current.hasPendingMutation || blocksRunSubmission(opened, sessionId)) {
            return@synchronized null
        }
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isSending = true,
                                sendErrorCategory = null,
                            ),
                    ),
            )
        sessionSendErrors.remove(sessionId)
        if (recordDraft) {
            recordSubmissionDraft(sessionId, input)
        }
        val requestEndpoint = mutableUiState.value.endpoint
        val recoverySessionKey = RecoverySessionKey(requestEndpoint, sessionId)
        val knownRunIds = knownRuns.mapTo(mutableSetOf()) { it.id }
        val attemptId = UUID.randomUUID().toString()
        val pendingCreate = PendingCreateState(knownRunIds, attemptId)
        pendingCreateSessions[recoverySessionKey] = pendingCreate
        val work =
            RunSubmissionWork(
                gateway = gateway,
                input = input,
                rejectRunId = rejectRunId,
                sessionId = sessionId,
                requestConnectionGeneration = requestConnectionGeneration,
                requestEndpoint = requestEndpoint,
                recoverySessionKey = recoverySessionKey,
                pendingCreate = pendingCreate,
                attemptId = attemptId,
                knownRunIds = knownRunIds,
            )
        PreparedRunSubmission(
            job = createRunJob(sessionId) { submitRun(work) },
            persistence =
                PendingSubmissionPersistence(
                    key = PendingRunSubmissionKey(requestEndpoint, sessionId),
                    recoveryKey = recoverySessionKey,
                    knownRunIds = knownRunIds,
                    attemptId = attemptId,
                ),
        )
    }

internal fun EntryStateHolder.submissionPersisted(
    run: Run,
    persistBeforeApply: Boolean,
    recoveryEntryPersisted: Boolean,
    submissionBindingSucceeded: Boolean,
): Boolean {
    return run.isActive() && persistBeforeApply && recoveryEntryPersisted && submissionBindingSucceeded
}

internal fun EntryStateHolder.submissionRunsAndState(
    opened: OpenSessionUiState,
    sessionId: SessionId,
): Pair<List<Run>, RunSubmissionState> {
    val knownRuns =
        visibleSessionRuns(sessionId).ifEmpty {
            (opened.activeRuns + listOfNotNull(opened.latestRun)).distinctBy { it.id }
        }
    val latestRun = knownRuns.latestRun() ?: opened.latestRun
    val state =
        RunSubmissionState(
            latestRun = latestRun,
            activeRuns = knownRuns.activeRuns(),
            isSubmissionPending = opened.isSending || runJobs.containsKey(sessionId),
        )
    return knownRuns to state
}

internal fun EntryStateHolder.recordSubmissionDraft(
    sessionId: SessionId,
    input: String,
) {
    val draft =
        PendingDraft(
            text = input,
            revision = sessionDraftRevisions[sessionId] ?: 0L,
        )
    pendingRunDrafts[sessionId] = draft
    sessionDrafts[sessionId] = input
}

internal fun EntryStateHolder.claimAndStartRunJob(submission: PreparedRunSubmission) {
    try {
        val claimed =
            runSubmissionUncertaintyStore.add(
                submission.persistence.key,
                submission.persistence.knownRunIds,
                submission.persistence.attemptId,
            )
        if (!claimed) error("A Gateway Run submission is already unresolved.")
    } catch (_: Exception) {
        abortUnclaimedRunJob(submission)
        return
    }
    submission.job.start()
}
