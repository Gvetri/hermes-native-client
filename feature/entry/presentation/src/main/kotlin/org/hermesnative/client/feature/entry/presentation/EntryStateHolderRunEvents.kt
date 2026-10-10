package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Job
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventStateTransition
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.recordRunEventSubmissionState(
    sessionId: SessionId,
    event: RunEvent,
    next: RunObservationState,
): Boolean {
    val submissionHasUnresolvedMarker =
        runSubmissionUncertaintyStore.contains(pendingRunSubmissionKey(sessionId)) ||
            unresolvedSubmissionSessions.contains(sessionId) ||
            sessionSendErrors[sessionId] == MessageSendErrorCategory.UNCERTAIN
    val submissionAwaitingConfirmation =
        (next.state.isTerminal() || !next.run.isActive()) &&
            uncertainSubmissionRunIds[sessionId] == event.runId &&
            submissionHasUnresolvedMarker
    if (next.state.isTerminal() || !next.run.isActive()) {
        forgetUnresolvedLocalRun(sessionId, next.run.id)
    }
    return submissionAwaitingConfirmation
}

internal fun EntryStateHolder.recordRunEventRunState(
    sessionId: SessionId,
    event: RunEvent,
    next: RunObservationState,
) {
    if (isLocallyOwnedRun(sessionId, event.runId)) {
        sessionRuns[sessionId] =
            mergeRuns(
                sessionRuns[sessionId].orEmpty(),
                listOf(next.run),
            )
    } else {
        authoritativeSessionRuns[sessionId] =
            mergeRuns(
                authoritativeSessionRuns[sessionId].orEmpty(),
                listOf(next.run),
            )
    }
}

internal fun EntryStateHolder.updateRunEventSessionUi(
    sessionId: SessionId,
    next: RunObservationState,
    submissionAwaitingConfirmation: Boolean,
) {
    val knownRuns = visibleSessionRuns(sessionId)
    val current = mutableUiState.value.sessionList ?: return
    val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
    val latestRun = knownRuns.latestRun() ?: next.run
    val latestObservation = latestObservationState(sessionId, knownRuns)
    val terminal = next.state.isTerminal() || !next.run.isActive()
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.copy(
                            latestRun = latestRun,
                            activeRuns = knownRuns.activeRuns(),
                            latestRunState = latestObservation?.state ?: next.state,
                            latestRunRetryAvailable =
                                latestRunRetryAvailable(knownRuns, latestObservation?.state ?: next.state),
                            activeResponse =
                                observedMessageUiState(latestObservation) ?: observedMessageUiState(next),
                            isRefreshing = opened.isRefreshing || terminal,
                            isStale = opened.isStale || terminal,
                            isReconciliationInProgress =
                                opened.isReconciliationInProgress || terminal,
                            composerText = opened.composerText,
                            sendErrorCategory =
                                if (submissionAwaitingConfirmation) {
                                    MessageSendErrorCategory.UNCERTAIN
                                } else {
                                    opened.sendErrorCategory
                                },
                            hasUnresolvedSubmission =
                                if (submissionAwaitingConfirmation) true else opened.hasUnresolvedSubmission,
                        ),
                ),
        )
}

internal fun EntryStateHolder.markRunObservationUncertain(
    sessionId: SessionId,
    runId: RunId,
    observationJob: Job,
) {
    synchronized(sessionRequestLock) {
        if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != runId) {
            return@synchronized
        }
        val previous = observationStateFor(sessionId, runId) ?: return@synchronized
        if (previous.state.isTerminal() || !previous.run.isActive()) return@synchronized
        val next = RunEventStateTransition.interrupted(previous)
        rememberObservationState(next)
        val current = mutableUiState.value.sessionList ?: return@synchronized
        val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return@synchronized
        val knownRuns = visibleSessionRuns(sessionId)
        val latestRun = knownRuns.latestRun()
        val latestObservation = latestObservationState(sessionId, knownRuns)
        val latestState =
            latestObservation?.state
                ?: next.state.takeIf { latestRun?.id == runId }
                ?: latestRun?.toRunPresentationState()
        val latestResponse =
            observedMessageUiState(latestObservation)
                ?: next.takeIf { latestRun?.id == runId }?.let { observedMessageUiState(it) }
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                latestRunState = latestState,
                                latestRunRetryAvailable = latestRunRetryAvailable(knownRuns, latestState),
                                activeResponse = latestResponse,
                            ),
                    ),
            )
    }
}

internal fun EntryStateHolder.observationStateFor(
    sessionId: SessionId,
    runId: RunId,
): RunObservationState? = runObservationStates[sessionId]?.get(runId)

internal fun EntryStateHolder.rememberObservationState(state: RunObservationState) {
    runObservationStates.getOrPut(state.run.sessionId) { mutableMapOf() }[state.run.id] = state
}
