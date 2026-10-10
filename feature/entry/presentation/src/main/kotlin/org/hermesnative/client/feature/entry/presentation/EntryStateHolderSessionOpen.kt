package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.showOpenedSessionFailure(requestGeneration: Long) {
    updateCurrentSessionRequest(requestGeneration) { current ->
        current.openedSession?.let { openedSession ->
            current.copy(
                openedSession =
                    openedSession.copy(
                        isRefreshing = false,
                        isStale = true,
                        errorCategory = SessionHistoryErrorCategory.GATEWAY_REQUEST_FAILED,
                    ),
            )
        } ?: current
    }
}

internal fun EntryStateHolder.openSession(sessionId: SessionId) {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val state = mutableUiState.value
            val sessionList = state.sessionList ?: return@synchronized null
            val sessionCanOpen = sessionList.sessions.any { it.id == sessionId }
            val mutationPending = sessionList.sessionMutations[sessionId]?.pendingAction != null
            if (sessionList.blocksSessionOpen() || !sessionCanOpen || mutationPending) {
                return@synchronized null
            }
            val replacedSessionId = sessionList.openedSession?.session?.id?.takeIf { it != sessionId }
            val request =
                SessionRequestContext(beginSessionRequest(), sessionList.searchQuery, offset = null)
            val requestConnectionGeneration = connectionGeneration
            mutableUiState.value =
                state.copy(
                    sessionList =
                        sessionList.copy(
                            openingSessionId = sessionId,
                            errorCategory = null,
                        ),
                )
            createSessionJob {
                openSessionJob(gateway, request, requestConnectionGeneration, sessionId, replacedSessionId)
            }
        } ?: return
    job.start()
}

internal suspend fun EntryStateHolder.openSessionJob(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
    requestConnectionGeneration: Long,
    sessionId: SessionId,
    replacedSessionId: SessionId?,
) {
    try {
        val openedSession = OpenSession(gateway).execute(sessionId)
        val applied =
            updateCurrentSessionRequest(request.generation) { current ->
                applyOpenedSessionResult(current, openedSession, sessionId)
            }
        if (applied) {
            applyOpenedSessionOutcome(
                gateway = gateway,
                request = request,
                requestConnectionGeneration = requestConnectionGeneration,
                openedSession = openedSession,
                replacedSessionId = replacedSessionId,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: GatewayException) {
        showSessionOpenFailure(request.generation, sessionId)
    } catch (_: Exception) {
        showSessionOpenFailure(request.generation, sessionId)
    }
}

internal fun EntryStateHolder.applyOpenedSessionResult(
    current: SessionListUiState,
    openedSession: OpenedSession,
    sessionId: SessionId,
): SessionListUiState {
    val authoritativeSession = openedSession.session.toSessionItemUiState()
    val knownRuns = rememberSessionRuns(sessionId, openedSession)
    val latestObservation = latestObservationState(sessionId, knownRuns)
    val latestRun = knownRuns.latestRun()
    val recoveryLoadBlocksSession =
        recoveryLoadPending ||
            recoveryLoadFailed ||
            sessionId in connectionRecoverySessionCounts ||
            sessionId in recoverySessionCounts
    return current.copy(
        sessions =
            current.sessions
                .map { item ->
                    if (item.id == sessionId) authoritativeSession else item
                }
                .orderedSessions(),
        sessionMutations = retainSessionMutation(current.sessionMutations, sessionId),
        openingSessionId = null,
        isStale = false,
        isUnavailable = false,
        openedSession =
            openedSession.toOpenSessionUiState(
                OpenSessionOverlay(
                    messages = toMessageUiStates(openedSession.history),
                    composerText = sessionDrafts[sessionId].orEmpty(),
                    sendErrorCategory = sendErrorCategoryFor(sessionId),
                    hasUnresolvedSubmission =
                        hasUnresolvedSubmission(sessionId) || recoveryLoadBlocksSession,
                    latestRun = latestRun,
                    activeRuns = knownRuns.activeRuns(),
                    isSending = runJobs.containsKey(sessionId),
                    latestRunState =
                        latestObservation?.state ?: latestRun?.toRunPresentationState(),
                    latestRunRetryAvailable =
                        latestRunRetryAvailable(
                            knownRuns,
                            latestObservation?.state ?: latestRun?.toRunPresentationState(),
                        ),
                    activeResponse = observedMessageUiState(latestObservation),
                ),
            ).copy(
                isRefreshing = recoveryLoadBlocksSession,
                isReconciliationInProgress =
                    openedSession.history.latestRun() != null || recoveryLoadBlocksSession,
            ),
        errorCategory = null,
    )
}

internal suspend fun EntryStateHolder.applyOpenedSessionOutcome(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
    requestConnectionGeneration: Long,
    openedSession: OpenedSession,
    replacedSessionId: SessionId?,
) {
    replacedSessionId?.let { releasedSessionId ->
        val released =
            synchronized(sessionRequestLock) { releaseRunObservation(releasedSessionId) }
        released.job?.cancel()
        released.observation?.close()
    }
    val sessionId = openedSession.session.id
    val pendingTimeoutRecovery =
        synchronized(sessionRequestLock) {
            pendingTimedOutSends[sessionId]
        }
    if (pendingTimeoutRecovery != null) {
        reconcileTimedOutSendNow(
            sessionId = sessionId,
            request = ReconciliationRequest(requestConnectionGeneration, request.generation),
            knownRunIds = pendingTimeoutRecovery.knownRunIds,
        )
    } else {
        val pendingSettledRecovery = prepareSettledSubmissionRecovery(sessionId)
        if (pendingSettledRecovery != null) {
            reconcileTimedOutSendNow(
                sessionId = sessionId,
                request = ReconciliationRequest(requestConnectionGeneration, request.generation),
                knownRunIds = pendingSettledRecovery.knownRunIds,
                resolveWhenNoNewRun = true,
            )
        } else {
            reconcileOpenedRun(
                OpenedRunReconcileContext(
                    sessionId = sessionId,
                    requestSessionGeneration = request.generation,
                    requestConnectionGeneration = requestConnectionGeneration,
                    sessionGateway = gateway,
                    runIdToReconcile = historyRunIdToReconcile(sessionId, openedSession),
                    restartObservation = true,
                    clearRefreshWhenNoRun = true,
                ),
            )
        }
    }
}

internal fun EntryStateHolder.showSessionOpenFailure(
    requestGeneration: Long,
    sessionId: SessionId,
) {
    updateCurrentSessionRequest(requestGeneration) { current ->
        current.copy(
            sessionMutations = retainRenameDraft(current.sessionMutations, sessionId),
            openingSessionId = null,
            isStale = true,
            isUnavailable = true,
            errorCategory = SessionListErrorCategory.SESSION_UNAVAILABLE,
        )
    }
}

internal fun EntryStateHolder.releaseRunObservation(sessionId: SessionId): RunObservationRelease {
    retainUnconfirmedTerminalRuns(sessionId)
    val job = runObservationJobs.remove(sessionId)
    val observation = runObservations.remove(sessionId)
    runObservationRunIds.remove(sessionId)
    return RunObservationRelease(job = job, observation = observation)
}

internal data class RunObservationRelease(
    val job: Job?,
    val observation: RunEventObservation?,
)

internal fun EntryStateHolder.returnToSessionList() {
    var observationToClose: RunEventObservation? = null
    var observationJobToCancel: Job? = null
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return
        if (current.createSession != null) {
            if (!current.createSession.isSubmitting) {
                mutableUiState.value =
                    mutableUiState.value.copy(
                        sessionList =
                            current.copy(
                                createSession = null,
                                errorCategory = null,
                            ),
                    )
            }
            return
        }
        current.openedSession?.session?.id?.let { sessionId ->
            val released = releaseRunObservation(sessionId)
            observationJobToCancel = released.job
            observationToClose = released.observation
        }
        beginSessionRequest()
        val retainedSession =
            current.openedSession?.session?.takeIf { session ->
                current.sessionMutations.containsKey(session.id) && current.sessions.none { it.id == session.id }
            }
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessions =
                            retainedSession?.let { mergeSessions(current.sessions, listOf(it)) }
                                ?: current.sessions,
                        openedSession = null,
                        openingSessionId = null,
                        errorCategory = null,
                    ),
            )
    }
    observationJobToCancel?.cancel()
    observationToClose?.close()
}

internal fun EntryStateHolder.updateComposerText(value: String) {
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return
        val opened = current.openedSession ?: return
        sessionDrafts[opened.session.id] = value
        sessionDraftRevisions[opened.session.id] =
            sessionDraftRevisions[opened.session.id]?.plus(1) ?: 1L
        sessionSendErrors.remove(opened.session.id)
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                composerText = value,
                                sendErrorCategory = sendErrorCategoryFor(opened.session.id),
                            ),
                    ),
            )
    }
}
