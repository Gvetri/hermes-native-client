package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.hermesnative.client.feature.entry.application.ObserveRun
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventStateTransition
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.startRunObservation(
    sessionId: SessionId,
    run: Run,
    expectedConnectionGeneration: Long? = null,
    expectedSessionGeneration: Long? = null,
    expectedHistoryGeneration: Long? = null,
) {
    val start =
        prepareRunObservation(
            sessionId = sessionId,
            run = run,
            expectedConnectionGeneration = expectedConnectionGeneration,
            expectedSessionGeneration = expectedSessionGeneration,
            expectedHistoryGeneration = expectedHistoryGeneration,
        )
    start.observationToClose?.close()
    start.observerJobToCancel?.cancel()
    if (start.handoffRequested) return
    val observationJob = start.jobToStart ?: return
    observationJob.invokeOnCompletion {
        restartObservationAfterCompletion(observationJob, sessionId)?.let { request ->
            startRunObservation(
                sessionId = sessionId,
                run = request.run,
                expectedConnectionGeneration = request.connectionGeneration,
                expectedSessionGeneration = request.sessionGeneration,
            )
        }
    }
    observationJob.start()
}

internal fun EntryStateHolder.prepareRunObservation(
    sessionId: SessionId,
    run: Run,
    expectedConnectionGeneration: Long?,
    expectedSessionGeneration: Long?,
    expectedHistoryGeneration: Long?,
): RunObservationStart =
    synchronized(sessionRequestLock) {
        if (
            !observationStartIsCurrent(
                sessionId = sessionId,
                expectedConnectionGeneration = expectedConnectionGeneration,
                expectedSessionGeneration = expectedSessionGeneration,
                expectedHistoryGeneration = expectedHistoryGeneration,
            )
        ) {
            return@synchronized RunObservationStart()
        }
        val current = mutableUiState.value.sessionList ?: return@synchronized RunObservationStart()
        if (!canStartObserving(sessionId, run, current.openedSession)) {
            return@synchronized RunObservationStart()
        }
        val currentRun = visibleSessionRuns(sessionId).lastOrNull { it.id == run.id }
        val currentAuthoritativeRun = authoritativeSessionRuns[sessionId]?.lastOrNull { it.id == run.id }
        val currentObservationState = observationStateFor(sessionId, run.id)
        if (
            currentRun?.isActive() == false ||
            currentAuthoritativeRun?.isActive() == false ||
            currentObservationState?.state?.isTerminal() == true
        ) {
            return@synchronized RunObservationStart()
        }
        val startRequest =
            ObserverStartRequest(run, connectionGeneration, sessionRequestGeneration)
        val existingJob = runObservationJobs[sessionId]
        if (existingJob != null) {
            startRunObservationHandoff(sessionId, run, startRequest, existingJob)
        } else {
            startNewRunObservation(sessionId, run, current)
        }
    }

internal fun EntryStateHolder.observationStartIsCurrent(
    sessionId: SessionId,
    expectedConnectionGeneration: Long?,
    expectedSessionGeneration: Long?,
    expectedHistoryGeneration: Long?,
): Boolean {
    val connectionMatches =
        expectedConnectionGeneration == null || expectedConnectionGeneration == connectionGeneration
    val sessionMatches =
        expectedSessionGeneration == null || expectedSessionGeneration == sessionRequestGeneration
    val historyMatches =
        expectedHistoryGeneration == null ||
            expectedHistoryGeneration == (authoritativeSessionHistoryGenerations[sessionId] ?: 0L)
    return connectionMatches && sessionMatches && historyMatches
}

internal fun EntryStateHolder.canStartObserving(
    sessionId: SessionId,
    run: Run,
    opened: OpenSessionUiState?,
): Boolean {
    val scopeIsActive = scope.coroutineContext[Job]?.isActive != false
    val gatewaysReady = sessionGateway != null && runGateway != null
    return opened != null &&
        opened.session.id == sessionId &&
        run.isActive() &&
        scopeIsActive &&
        gatewaysReady
}

internal fun EntryStateHolder.startRunObservationHandoff(
    sessionId: SessionId,
    run: Run,
    startRequest: ObserverStartRequest,
    existingJob: Job,
): RunObservationStart {
    var observerJobToCancel: Job? = null
    var observationToClose: RunEventObservation? = null
    if (runObservationRunIds[sessionId] != run.id || existingJob.isCancelled) {
        pendingRunObservationRequests[sessionId] = startRequest
    }
    if (runObservationRunIds[sessionId] != run.id) {
        observerJobToCancel = existingJob
        observationToClose = runObservations.remove(sessionId)
        runObservationRunIds.remove(sessionId)
    }
    return RunObservationStart(
        observerJobToCancel = observerJobToCancel,
        observationToClose = observationToClose,
        handoffRequested = true,
    )
}

internal fun EntryStateHolder.startNewRunObservation(
    sessionId: SessionId,
    run: Run,
    current: SessionListUiState,
): RunObservationStart {
    val opened = current.openedSession ?: return RunObservationStart()
    pendingRunObservationRequests.remove(sessionId)
    val state =
        observationStateFor(sessionId, run.id)
            ?: RunEventStateTransition.initial(run)
    rememberObservationState(state)
    val knownRuns = visibleSessionRuns(sessionId)
    val latestRun = knownRuns.latestRun()
    val latestObservation = latestObservationState(sessionId, knownRuns)
    val latestState =
        latestObservation?.state
            ?: state.takeIf { latestRun == null || latestRun.id == run.id }?.state
            ?: latestRun?.toRunPresentationState()
    val latestResponse =
        observedMessageUiState(latestObservation)
            ?: state
                .takeIf { latestRun == null || latestRun.id == run.id }
                ?.let { observedMessageUiState(it) }
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
    val observationJob =
        scope.launch(start = CoroutineStart.LAZY) {
            observeRun(
                sessionId = sessionId,
                run = run,
            )
        }
    runObservationJobs[sessionId] = observationJob
    runObservationRunIds[sessionId] = run.id
    return RunObservationStart(jobToStart = observationJob)
}

internal fun EntryStateHolder.restartObservationAfterCompletion(
    observationJob: Job,
    sessionId: SessionId,
): ObserverStartRequest? =
    synchronized(sessionRequestLock) {
        if (runObservationJobs[sessionId] !== observationJob) {
            null
        } else {
            runObservationJobs.remove(sessionId)
            runObservationRunIds.remove(sessionId)
            val pendingRequest = pendingRunObservationRequests.remove(sessionId)
            val nextRun =
                pendingRequest?.run?.takeIf(Run::isActive)
                    ?: visibleSessionRuns(sessionId).latestActiveRun()
            nextRun?.let { run -> queuedObservationRestart(observationJob, sessionId, pendingRequest, run) }
        }
    }

internal fun EntryStateHolder.queuedObservationRestart(
    observationJob: Job,
    sessionId: SessionId,
    pendingRequest: ObserverStartRequest?,
    nextRun: Run,
): ObserverStartRequest? {
    val restartRequested = observationJob.isCancelled || pendingRequest != null
    val sessionIsOpen = mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
    val gatewaysReady = runGateway != null && sessionGateway != null
    return if (restartRequested && sessionIsOpen && gatewaysReady) {
        ObserverStartRequest(nextRun, connectionGeneration, sessionRequestGeneration)
    } else {
        null
    }
}

internal suspend fun EntryStateHolder.observeRun(
    sessionId: SessionId,
    run: Run,
) {
    val observationJob = currentCoroutineContext()[Job] ?: return
    val gateway = observationGatewayFor(sessionId) ?: return
    var observation: RunEventObservation? = null
    var lateObservation: RunEventObservation? = null
    var shouldReconcile = false
    try {
        currentCoroutineContext().ensureActive()
        val activeObservation =
            runInterruptible {
                ObserveRun(gateway).execute(run.id).also { lateObservation = it }
            }
        observation = activeObservation
        if (registerActiveObservation(sessionId, run, observationJob, activeObservation)) {
            drainObservedEvents(sessionId, run, observationJob, activeObservation)
            shouldReconcile = true
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (currentCoroutineContext()[Job]?.isActive == true) {
            markRunObservationUncertain(sessionId, run.id, observationJob)
            shouldReconcile = true
        }
    } finally {
        closeObservation(sessionId, observation, lateObservation)
    }
    reconcileObservationOutcome(sessionId, run, observationJob, shouldReconcile)
}

internal fun EntryStateHolder.observationGatewayFor(sessionId: SessionId): RunGatewayPort? =
    synchronized(sessionRequestLock) {
        runGateway?.takeIf {
            mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
        }
    }
