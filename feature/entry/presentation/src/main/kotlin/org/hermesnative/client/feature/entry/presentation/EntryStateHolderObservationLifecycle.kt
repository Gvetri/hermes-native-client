package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventStateTransition
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal

internal fun EntryStateHolder.isCurrentObservationJob(
    sessionId: SessionId,
    runId: RunId,
    observationJob: Job,
): Boolean {
    val jobActive = observationJob.isActive
    val sessionIsOpen = mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
    return jobActive &&
        sessionIsOpen &&
        runObservationJobs[sessionId] === observationJob &&
        runObservationRunIds[sessionId] == runId
}

internal fun EntryStateHolder.registerActiveObservation(
    sessionId: SessionId,
    run: Run,
    observationJob: Job,
    activeObservation: RunEventObservation,
): Boolean =
    synchronized(sessionRequestLock) {
        if (isCurrentObservationJob(sessionId, run.id, observationJob)) {
            runObservations[sessionId] = activeObservation
            true
        } else {
            false
        }
    }

internal suspend fun EntryStateHolder.drainObservedEvents(
    sessionId: SessionId,
    run: Run,
    observationJob: Job,
    activeObservation: RunEventObservation,
) {
    var reachedTerminalState = false
    for (event in activeObservation) {
        currentCoroutineContext().ensureActive()
        applyRunEvent(sessionId, event, observationJob)
        reachedTerminalState =
            synchronized(sessionRequestLock) {
                observationStateFor(sessionId, run.id)
                    ?.let { state -> state.state.isTerminal() || !state.run.isActive() } == true
            }
        if (reachedTerminalState) break
    }
    if (!reachedTerminalState) {
        markRunObservationUncertain(sessionId, run.id, observationJob)
    }
}

internal fun EntryStateHolder.closeObservation(
    sessionId: SessionId,
    observation: RunEventObservation?,
    lateObservation: RunEventObservation?,
) {
    if (observation == null) {
        lateObservation?.close()
    } else {
        observation.close()
    }
    synchronized(sessionRequestLock) {
        if (runObservations[sessionId] === observation) {
            runObservations.remove(sessionId)
        }
    }
}

internal suspend fun EntryStateHolder.reconcileObservationOutcome(
    sessionId: SessionId,
    run: Run,
    observationJob: Job,
    shouldReconcile: Boolean,
) {
    val reconciliationRequest =
        if (shouldReconcile) {
            currentObservationReconciliationRequest(sessionId, run.id, observationJob)
        } else {
            null
        }
    if (reconciliationRequest != null) {
        currentCoroutineContext().ensureActive()
        val gateway = reconciliationGateway(reconciliationRequest)
        if (gateway != null) {
            reconcileRun(
                sessionId = sessionId,
                runId = run.id,
                requestConnectionGeneration = reconciliationRequest.connectionGeneration,
                requestSessionGeneration = reconciliationRequest.sessionGeneration,
                sessionGateway = gateway,
            )
        }
    }
}

internal fun EntryStateHolder.reconciliationGateway(request: ReconciliationRequest): SessionGatewayPort? =
    synchronized(sessionRequestLock) {
        sessionGateway?.takeIf {
            connectionGeneration == request.connectionGeneration &&
                sessionRequestGeneration == request.sessionGeneration
        }
    }

internal fun EntryStateHolder.currentObservationReconciliationRequest(
    sessionId: SessionId,
    runId: RunId,
    observationJob: Job,
): ReconciliationRequest? =
    synchronized(sessionRequestLock) {
        val observationIsCurrent = isCurrentObservationJob(sessionId, runId, observationJob)
        if (observationIsCurrent && observationStateFor(sessionId, runId) != null) {
            ReconciliationRequest(connectionGeneration, sessionRequestGeneration)
        } else {
            null
        }
    }

internal fun EntryStateHolder.shouldReconcileCurrentSession(
    requestConnectionGeneration: Long,
    requestSessionGeneration: Long,
    sessionId: SessionId,
): Boolean =
    synchronized(sessionRequestLock) {
        connectionGeneration == requestConnectionGeneration &&
            sessionRequestGeneration == requestSessionGeneration &&
            mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
    }

internal fun EntryStateHolder.applyRunEvent(
    sessionId: SessionId,
    event: RunEvent,
    observationJob: Job,
) {
    synchronized(sessionRequestLock) {
        if (runObservationJobs[sessionId] !== observationJob || runObservationRunIds[sessionId] != event.runId) {
            return@synchronized
        }
        val previous = observationStateFor(sessionId, event.runId) ?: return@synchronized
        val next = RunEventStateTransition.apply(previous, event)
        rememberObservationState(next)
        if (next.state != previous.state) {
            postTerminalRunStatusNotificationOnce(next.run, next.state)
        }
        applyRunEventState(sessionId, event, next)
    }
}

internal fun EntryStateHolder.applyRunEventState(
    sessionId: SessionId,
    event: RunEvent,
    next: RunObservationState,
) {
    val submissionAwaitingConfirmation = recordRunEventSubmissionState(sessionId, event, next)
    recordRunEventRunState(sessionId, event, next)
    updateRunEventSessionUi(sessionId, next, submissionAwaitingConfirmation)
}
