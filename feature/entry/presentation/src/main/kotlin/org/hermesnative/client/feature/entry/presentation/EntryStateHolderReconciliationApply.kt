package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.AuthoritativeRunReconciliation
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunReconciliationDecision
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.runs
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.applyReconciliationLocked(
    request: AuthoritativeReconciliationRequest,
    reconciliation: AuthoritativeRunReconciliation,
): ReconciledRunApplication =
    synchronized(sessionRequestLock) {
        val requestIsCurrent =
            connectionGeneration == request.connectionGeneration &&
                (!request.updateVisibleUi || sessionRequestGeneration == request.sessionGeneration)
        if (!requestIsCurrent) return@synchronized ReconciledRunApplication()
        val shouldUpdateVisibleUi =
            request.updateVisibleUi &&
                request.sessionGeneration != null &&
                sessionRequestGeneration == request.sessionGeneration
        val run = reconciliation.run
        val decision = reconciliation.decision
        val terminalRunIds = terminalRunIdsFor(request.sessionId, reconciliation)
        val session = visibleSessionTarget(request.sessionId, shouldUpdateVisibleUi)
        if (historyGenerationMatches(request.sessionId, request.historyGeneration)) {
            val release = recordReconciledRunState(request.sessionId, reconciliation, terminalRunIds)
            if (decision == RunReconciliationDecision.CONFIRMED) {
                val handles = confirmedRecoveryHandles(request, run)
                val canClearSendState =
                    clearConfirmedSubmissionState(request.sessionId, reconciliation, release.wasActivelyObserved)
                updateConfirmedRunSessionUi(
                    sessionId = request.sessionId,
                    reconciliation = reconciliation,
                    canClearSendState = canClearSendState,
                    session = session,
                )
                ReconciledRunApplication(
                    observationJobToCancel = release.job,
                    observationToClose = release.observation,
                    recoveryEntryToRemove = handles.first,
                    recoveryEndpointToRemove = handles.second,
                )
            } else {
                updateUnconfirmedRunSessionUi(
                    sessionId = request.sessionId,
                    run = run,
                    session = session,
                )
                ReconciledRunApplication(
                    observationJobToCancel = release.job,
                    observationToClose = release.observation,
                )
            }
        } else if (
            decision == RunReconciliationDecision.CONFIRMED &&
            !run.isActive() &&
            historyConfirmsTerminalRun(request.sessionId, run.id)
        ) {
            ReconciledRunApplication(
                recoveryEntryToRemove = RunRecoveryEntry(sessionId = request.sessionId, runId = run.id),
                recoveryEndpointToRemove = mutableUiState.value.endpoint.takeIf(String::isNotBlank),
            )
        } else {
            ReconciledRunApplication()
        }
    }

internal fun EntryStateHolder.confirmedRecoveryHandles(
    request: AuthoritativeReconciliationRequest,
    run: Run,
): Pair<RunRecoveryEntry?, String?> =
    if (run.isActive()) {
        null to null
    } else {
        RunRecoveryEntry(sessionId = request.sessionId, runId = run.id) to
            mutableUiState.value.endpoint.takeIf(String::isNotBlank)
    }

internal fun EntryStateHolder.visibleSessionTarget(
    sessionId: SessionId,
    shouldUpdate: Boolean,
): Pair<SessionListUiState, OpenSessionUiState>? =
    if (!shouldUpdate) {
        null
    } else {
        openedSessionFor(sessionId)
    }

internal fun EntryStateHolder.terminalRunIdsFor(
    sessionId: SessionId,
    reconciliation: AuthoritativeRunReconciliation,
): Set<RunId> {
    val run = reconciliation.run
    val currentlyObservedRunId = runObservationRunIds[sessionId]
    val terminalRunIds =
        reconciliation.history.runs()
            .filterNot(Run::isActive)
            .filter { it.id != currentlyObservedRunId || it.id == run.id }
            .mapTo(mutableSetOf()) { it.id }
    if (reconciliation.decision != RunReconciliationDecision.CONFIRMED) {
        terminalRunIds.remove(run.id)
    }
    return terminalRunIds
}

internal fun EntryStateHolder.historyGenerationMatches(
    sessionId: SessionId,
    requestHistoryGeneration: Long?,
): Boolean =
    requestHistoryGeneration == null ||
        (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) == requestHistoryGeneration

internal fun EntryStateHolder.historyConfirmsTerminalRun(
    sessionId: SessionId,
    runId: RunId,
): Boolean =
    authoritativeSessionRuns[sessionId]
        .orEmpty()
        .any { knownRun -> knownRun.id == runId && !knownRun.isActive() }

internal fun EntryStateHolder.recordReconciledRunState(
    sessionId: SessionId,
    reconciliation: AuthoritativeRunReconciliation,
    terminalRunIds: Set<RunId>,
): ReconciledObservationRelease {
    val run = reconciliation.run
    val wasActivelyObserved = runObservationRunIds[sessionId] == run.id
    forgetConfirmedObservationStates(sessionId, terminalRunIds)
    val incomingAuthoritativeRuns = reconciliation.history.runs() + run
    val incomingAuthoritativeRunIds = incomingAuthoritativeRuns.mapTo(mutableSetOf()) { it.id }
    val retainedAuthoritativeRuns =
        authoritativeSessionRuns[sessionId]
            .orEmpty()
            .filter { existing -> existing.id !in incomingAuthoritativeRunIds }
    authoritativeSessionRuns[sessionId] =
        mergeRuns(retainedAuthoritativeRuns, incomingAuthoritativeRuns)
    if (isLocallyOwnedRun(sessionId, run.id)) {
        sessionRuns[sessionId] = mergeRuns(sessionRuns[sessionId].orEmpty(), listOf(run))
    }
    if (!run.isActive() && runObservationRunIds[sessionId] == run.id) {
        val job = runObservationJobs[sessionId]
        val observation = runObservations.remove(sessionId)
        runObservationRunIds.remove(sessionId)
        return ReconciledObservationRelease(
            job = job,
            observation = observation,
            wasActivelyObserved = wasActivelyObserved,
        )
    }
    return ReconciledObservationRelease(wasActivelyObserved = wasActivelyObserved)
}

internal fun EntryStateHolder.clearConfirmedSubmissionState(
    sessionId: SessionId,
    reconciliation: AuthoritativeRunReconciliation,
    wasActivelyObserved: Boolean,
): Boolean {
    val run = reconciliation.run
    val canClearSendState = resolveBoundSubmission(sessionId, run)
    if (!run.isActive()) {
        forgetUnresolvedLocalRun(sessionId, run.id)
    }
    if (canClearSendState) {
        uncertainSubmissionRunIds.remove(sessionId)
        uncertainSubmissionAttemptIds.remove(sessionId)
        sessionSendErrors.remove(sessionId)
        val uncertainDraft = uncertainSendDrafts.remove(sessionId)
        if (uncertainDraft != null && sessionDraftRevisions[sessionId] == uncertainDraft.revision) {
            sessionDrafts.remove(sessionId)
        }
        ambiguousSubmissionSessions.remove(sessionId)
    }
    forgetObservationState(sessionId, run.id)
    if (wasActivelyObserved) {
        postTerminalRunStatusNotificationOnce(run, run.toRunPresentationState())
    }
    return canClearSendState
}

internal fun EntryStateHolder.resolveBoundSubmission(
    sessionId: SessionId,
    run: Run,
): Boolean {
    val pendingKey = pendingRunSubmissionKey(sessionId)
    val uncertaintySnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
    val unresolvedAttemptId =
        unresolvedLocalRunAttempts[recoverySessionKey(sessionId)]?.get(run.id)
    val trackedAttemptId =
        uncertainSubmissionAttemptIds[sessionId] ?: unresolvedAttemptId
    val hasBoundSubmission =
        uncertainSubmissionRunIds[sessionId] == run.id ||
            unresolvedLocalRunIds[recoverySessionKey(sessionId)]?.contains(run.id) == true ||
            uncertaintySnapshot?.boundRunId == run.id
    val markerMatchesTrackedAttempt =
        trackedAttemptId == null ||
            uncertaintySnapshot == null ||
            uncertaintySnapshot.attemptId == trackedAttemptId
    val isBoundSubmission = hasBoundSubmission && markerMatchesTrackedAttempt
    val uncertaintyRemoved =
        !isBoundSubmission ||
            run.isActive() ||
            uncertaintySnapshot == null ||
            runCatching {
                runSubmissionUncertaintyStore.removeIfSnapshotMatches(pendingKey, uncertaintySnapshot)
            }.getOrDefault(false)
    return isBoundSubmission && uncertaintyRemoved
}

internal fun EntryStateHolder.updateConfirmedRunSessionUi(
    sessionId: SessionId,
    reconciliation: AuthoritativeRunReconciliation,
    canClearSendState: Boolean,
    session: Pair<SessionListUiState, OpenSessionUiState>?,
) {
    if (session == null) return
    val (current, opened) = session
    val knownRuns = visibleSessionRuns(sessionId)
    val latestObservation = latestObservationState(sessionId, knownRuns)
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.withRunSnapshot(knownRuns, latestObservation).copy(
                            messages = toMessageUiStates(reconciliation.history),
                            composerText = sessionDrafts[sessionId].orEmpty(),
                            sendErrorCategory = if (canClearSendState) null else opened.sendErrorCategory,
                            hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                            errorCategory = null,
                            isStale = false,
                        ),
                ),
        )
}
