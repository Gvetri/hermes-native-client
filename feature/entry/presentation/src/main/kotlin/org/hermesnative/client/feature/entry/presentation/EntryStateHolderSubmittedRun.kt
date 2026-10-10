package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.cancel
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEventStateTransition
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive

internal fun EntryStateHolder.applySubmittedRun(
    sessionId: SessionId,
    run: Run,
    requestConnectionGeneration: Long,
    requestEndpoint: String,
    attemptId: String,
): Boolean {
    val preparation = prepareSubmittedRun(sessionId, run, requestConnectionGeneration, requestEndpoint, attemptId)
    val result =
        applySubmittedRunLocked(
            SubmittedRunApplication(
                sessionId = sessionId,
                run = run,
                requestConnectionGeneration = requestConnectionGeneration,
                requestEndpoint = requestEndpoint,
                attemptId = attemptId,
                pendingKey = preparation.pendingKey,
                submissionBindingSucceeded = preparation.submissionBindingSucceeded,
                recoveryPersistenceFailed = preparation.recoveryPersistenceFailed,
            ),
        )
    if (!result.applied && result.persistAfterDisconnect && !preparation.persistBeforeApply) {
        val entry = RunRecoveryEntry(sessionId = sessionId, runId = run.id)
        if (run.isActive()) {
            val persisted = persistDisconnectedRecoveryEntry(endpoint = requestEndpoint, entry = entry)
            if (persisted) {
                runCatching { runSubmissionUncertaintyStore.remove(preparation.pendingKey, attemptId) }
            }
        } else {
            reconcilePersistedRecoveryEntryIfConnected(requestEndpoint, entry)
        }
    }
    result.observationToClose?.close()
    result.observationJobToCancel?.cancel()
    if (result.applied && result.shouldObserve) {
        startRunObservation(
            sessionId = sessionId,
            run = run,
            expectedConnectionGeneration = requestConnectionGeneration,
            expectedSessionGeneration = result.requestSessionGeneration,
        )
    }
    return result.applied
}

internal fun EntryStateHolder.prepareSubmittedRun(
    sessionId: SessionId,
    run: Run,
    requestConnectionGeneration: Long,
    requestEndpoint: String,
    attemptId: String,
): SubmittedRunPreparation {
    val pendingKey = PendingRunSubmissionKey(requestEndpoint, sessionId)
    val submissionBindingSucceeded = bindSubmittedRun(pendingKey, run.id, attemptId)
    val persistBeforeApply = persistBeforeApply(run, requestConnectionGeneration)
    val recoveryEntryPersisted =
        persistBeforeApply &&
            persistOrQueueRecoveryEntry(
                requestEndpoint,
                RunRecoveryEntry(sessionId = sessionId, runId = run.id),
            )
    val recoveryPersistenceFailed =
        if (submissionPersisted(run, persistBeforeApply, recoveryEntryPersisted, submissionBindingSucceeded)) {
            !runCatching { runSubmissionUncertaintyStore.remove(pendingKey, attemptId) }.getOrDefault(false)
        } else if (run.isActive()) {
            persistBeforeApply && (!recoveryEntryPersisted || !submissionBindingSucceeded)
        } else {
            !submissionBindingSucceeded
        }
    return SubmittedRunPreparation(
        pendingKey = pendingKey,
        submissionBindingSucceeded = submissionBindingSucceeded,
        persistBeforeApply = persistBeforeApply,
        recoveryPersistenceFailed = recoveryPersistenceFailed,
    )
}

internal fun EntryStateHolder.bindSubmittedRun(
    pendingKey: PendingRunSubmissionKey,
    runId: RunId,
    attemptId: String,
): Boolean = runCatching { runSubmissionUncertaintyStore.bindRun(pendingKey, runId, attemptId) }.getOrDefault(false)

internal fun EntryStateHolder.persistBeforeApply(
    run: Run,
    requestConnectionGeneration: Long,
): Boolean =
    run.isActive() &&
        synchronized(sessionRequestLock) {
            connectionGeneration == requestConnectionGeneration
        }

internal fun EntryStateHolder.applySubmittedRunLocked(work: SubmittedRunApplication): SubmittedRunApplicationResult =
    synchronized(sessionRequestLock) {
        if (connectionGeneration != work.requestConnectionGeneration) {
            if (work.run.isActive()) {
                rememberUnresolvedLocalRun(
                    work.requestEndpoint,
                    work.sessionId,
                    work.run.id,
                    work.attemptId,
                )
            }
            return@synchronized SubmittedRunApplicationResult(
                applied = false,
                persistAfterDisconnect = work.run.isActive(),
            )
        }
        val shouldObserve = work.run.isActive()
        recordSubmittedRunState(work)
        val observationState = markSubmittedRunObservationState(work, shouldObserve)
        val releases = updateSubmittedRunUi(work, observationState)
        SubmittedRunApplicationResult(
            applied = true,
            requestSessionGeneration = sessionRequestGeneration,
            shouldObserve = shouldObserve,
            observationJobToCancel = releases.job,
            observationToClose = releases.observation,
        )
    }

internal fun EntryStateHolder.recordSubmittedRunState(application: SubmittedRunApplication) {
    if (application.recoveryPersistenceFailed) {
        runCatching {
            runSubmissionUncertaintyStore.markAmbiguous(application.pendingKey, application.attemptId)
        }
    }
    if (!application.submissionBindingSucceeded) {
        rememberUnresolvedLocalRun(
            application.requestEndpoint,
            application.sessionId,
            application.run.id,
            application.attemptId,
        )
        unresolvedSubmissionSessions += application.sessionId
    } else {
        uncertainSubmissionRunIds[application.sessionId] = application.run.id
        uncertainSubmissionAttemptIds[application.sessionId] = application.attemptId
    }
    val submittedDraft =
        if (application.submissionBindingSucceeded) pendingRunDrafts.remove(application.sessionId) else null
    if (!application.submissionBindingSucceeded || application.recoveryPersistenceFailed) {
        if (application.submissionBindingSucceeded) {
            submittedDraft?.let { uncertainSendDrafts[application.sessionId] = it }
        }
        sessionSendErrors[application.sessionId] = MessageSendErrorCategory.UNCERTAIN
    } else if (
        submittedDraft != null &&
        sessionDraftRevisions[application.sessionId] == submittedDraft.revision
    ) {
        sessionSendErrors.remove(application.sessionId)
        sessionDrafts.remove(application.sessionId)
    } else {
        sessionSendErrors.remove(application.sessionId)
    }
    sessionRuns[application.sessionId] =
        (sessionRuns[application.sessionId].orEmpty().filterNot { it.id == application.run.id } + application.run)
    val recoveryKey = RecoverySessionKey(application.requestEndpoint, application.sessionId)
    pendingCreateSessions[recoveryKey]
        ?.takeIf { it.attemptId == application.attemptId }
        ?.let { forgetPendingCreate(recoveryKey, it) }
}

internal fun EntryStateHolder.markSubmittedRunObservationState(
    application: SubmittedRunApplication,
    shouldObserve: Boolean,
): RunObservationState {
    val observationState =
        if (shouldObserve && !application.recoveryPersistenceFailed) {
            observationStateFor(application.sessionId, application.run.id)
                ?: RunEventStateTransition.initial(application.run)
        } else {
            uncertainObservationState(
                application.run,
                observationStateFor(application.sessionId, application.run.id),
            )
        }
    rememberObservationState(observationState)
    return observationState
}

internal fun EntryStateHolder.updateSubmittedRunUi(
    application: SubmittedRunApplication,
    observationState: RunObservationState,
): ReconciledObservationRelease {
    val current = mutableUiState.value.sessionList
    val opened = current?.openedSession?.takeIf { it.session.id == application.sessionId }
    if (current == null || opened == null) {
        return ReconciledObservationRelease()
    }
    val jobToCancel = runObservationJobs[application.sessionId]
    val observationToClose = runObservations.remove(application.sessionId)
    runObservationRunIds.remove(application.sessionId)
    val knownRuns = visibleSessionRuns(application.sessionId)
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.copy(
                            composerText = sessionDrafts[application.sessionId].orEmpty(),
                            latestRun = knownRuns.latestRun() ?: application.run,
                            activeRuns = knownRuns.activeRuns(),
                            isSending = false,
                            sendErrorCategory =
                                if (application.recoveryPersistenceFailed) {
                                    MessageSendErrorCategory.UNCERTAIN
                                } else {
                                    null
                                },
                            hasUnresolvedSubmission = hasUnresolvedSubmission(application.sessionId),
                            latestRunState = observationState.state,
                            latestRunRetryAvailable =
                                latestRunRetryAvailable(knownRuns, observationState.state),
                            activeResponse = observedMessageUiState(observationState),
                            isReconciliationInProgress = !application.run.isActive(),
                            isStale = !application.run.isActive() || application.recoveryPersistenceFailed,
                        ),
                ),
        )
    return ReconciledObservationRelease(job = jobToCancel, observation = observationToClose)
}
