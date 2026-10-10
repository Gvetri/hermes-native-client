package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticEventType
import org.hermesnative.client.feature.entry.domain.LocalDiagnosticStatus
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.refreshOpenedSession(gateway: SessionGatewayPort): Job? =
    synchronized(sessionRequestLock) {
        val state = mutableUiState.value
        val sessionList = state.sessionList ?: return@synchronized null
        val openedSession = sessionList.openedSession ?: return@synchronized null
        if (openedSession.isRefreshing || sessionList.hasActiveRequest) return@synchronized null

        val runIdToReconcile =
            visibleSessionRuns(openedSession.session.id)
                .latestActiveRun()
                ?.id
                ?: latestObservedObservationState(
                    openedSession.session.id,
                    visibleSessionRuns(openedSession.session.id),
                )?.run?.id
        val requestGeneration = beginSessionRequest()
        val requestConnectionGeneration = connectionGeneration
        val request = SessionRequestContext(requestGeneration, sessionList.searchQuery, offset = null)
        mutableUiState.value =
            state.copy(
                sessionList =
                    sessionList.copy(
                        openedSession =
                            openedSession.copy(
                                isRefreshing = true,
                                errorCategory = null,
                            ),
                        errorCategory = null,
                    ),
            )
        createSessionJob {
            loadOpenedSession(
                gateway = gateway,
                request = request,
                sessionId = openedSession.session.id,
                requestConnectionGeneration = requestConnectionGeneration,
                runIdToReconcile = runIdToReconcile,
            )
        }
    }

internal suspend fun EntryStateHolder.loadOpenedSession(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
    sessionId: SessionId,
    requestConnectionGeneration: Long,
    runIdToReconcile: RunId? = null,
) {
    try {
        val openedSession = OpenSession(gateway).execute(sessionId)
        val applied =
            updateCurrentSessionRequest(request.generation) { current ->
                applyRefreshedSessionResult(current, openedSession, sessionId)
            }
        if (applied) {
            continueRefreshedSessionRecovery(
                gateway = gateway,
                request = request,
                requestConnectionGeneration = requestConnectionGeneration,
                openedSession = openedSession,
                runIdToReconcile = runIdToReconcile,
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: GatewayException) {
        showOpenedSessionFailure(request.generation)
    } catch (_: Exception) {
        showOpenedSessionFailure(request.generation)
    }
}

internal fun EntryStateHolder.applyRefreshedSessionResult(
    current: SessionListUiState,
    openedSession: OpenedSession,
    sessionId: SessionId,
): SessionListUiState {
    val previous = current.openedSession ?: return current
    val authoritativeSession = openedSession.session.toSessionItemUiState()
    val knownRuns = rememberSessionRuns(sessionId, openedSession)
    val latestObservation = latestObservationState(sessionId, knownRuns)
    val latestRun = knownRuns.latestRun()
    return current.copy(
        sessions =
            current.sessions
                .map { item -> if (item.id == sessionId) authoritativeSession else item }
                .orderedSessions(),
        openedSession =
            openedSession.toOpenSessionUiState(
                OpenSessionOverlay(
                    messages = toMessageUiStates(openedSession.history),
                    composerText = sessionDrafts[sessionId] ?: previous.composerText,
                    sendErrorCategory = sendErrorCategoryFor(sessionId),
                    hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                    latestRun = latestRun,
                    activeRuns = knownRuns.activeRuns(),
                    isSending = previous.isSending || runJobs.containsKey(sessionId),
                    latestRunState =
                        latestObservation?.state ?: latestRun?.toRunPresentationState(),
                    latestRunRetryAvailable =
                        latestRunRetryAvailable(
                            knownRuns,
                            latestObservation?.state ?: latestRun?.toRunPresentationState(),
                        ),
                    activeResponse = observedMessageUiState(latestObservation),
                    isRefreshing = previous.isRefreshing,
                ),
            ).copy(
                isReconciliationInProgress =
                    previous.isReconciliationInProgress ||
                        openedSession.history.latestRun() != null,
            ),
        errorCategory = null,
    )
}

internal suspend fun EntryStateHolder.continueRefreshedSessionRecovery(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
    requestConnectionGeneration: Long,
    openedSession: OpenedSession,
    runIdToReconcile: RunId?,
) {
    val sessionId = openedSession.session.id
    val pendingTimeoutRecovery =
        synchronized(sessionRequestLock) {
            pendingTimedOutSends[sessionId]
        }
    val pendingSettledRecovery =
        if (pendingTimeoutRecovery == null) {
            prepareSettledSubmissionRecovery(sessionId)
        } else {
            null
        }
    if (pendingTimeoutRecovery != null) {
        reconcileTimedOutSendNow(
            sessionId = sessionId,
            request = ReconciliationRequest(requestConnectionGeneration, request.generation),
            knownRunIds = pendingTimeoutRecovery.knownRunIds,
        )
    } else if (pendingSettledRecovery != null) {
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
                runIdToReconcile = runIdToReconcile ?: historyRunIdToReconcile(sessionId, openedSession),
                restartObservation = true,
                clearRefreshWhenNoRun = true,
            ),
        )
    }
}

internal fun EntryStateHolder.loadMoreSessions() {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val state = mutableUiState.value
            val sessionList = state.sessionList ?: return@synchronized null
            val offset = sessionList.nextOffset ?: return@synchronized null
            if (sessionList.blocksLoadingMore()) return@synchronized null

            val request =
                SessionRequestContext(beginSessionRequest(), sessionList.searchQuery, offset)
            mutableUiState.value =
                state.copy(
                    sessionList =
                        sessionList.copy(
                            isLoadingMore = true,
                            errorCategory = null,
                        ),
                )
            loadMorePageJob(gateway, request)
        } ?: return
    job.start()
}

internal fun EntryStateHolder.loadMorePageJob(
    gateway: SessionGatewayPort,
    request: SessionRequestContext,
): Job =
    createSessionJob {
        try {
            val page = LoadSessionList(gateway).execute(sessionListRequest(request.offset))
            updateCurrentSessionRequest(request.generation, request.offset) { current ->
                current.copy(
                    sessions =
                        mergeSessions(
                            current.sessions,
                            page.sessions.map { it.toSessionItemUiState() },
                        ),
                    openedSession = current.openedSession?.withSessionMetadata(page.sessions),
                    nextOffset = page.nextOffset,
                    isLoadingMore = false,
                    isStale = false,
                    isUnavailable = false,
                    errorCategory = null,
                )
            }
            recordDiagnostic(LocalDiagnosticEventType.SESSION_LIST_LOAD, LocalDiagnosticStatus.SUCCEEDED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayException) {
            showSessionListFailure(request.generation, request.offset, preserveSessions = true)
        } catch (_: Exception) {
            showSessionListFailure(request.generation, request.offset, preserveSessions = true)
        }
    }
