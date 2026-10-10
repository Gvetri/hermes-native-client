package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.application.OpenedSession
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.runs

internal fun SessionMutationUiState.hasNoPendingWork(): Boolean =
    rename == null && delete == null && pendingAction == null && errorCategory == null && retryAction == null

internal fun SessionListUiState.sessionForMutation(sessionId: SessionId): SessionItemUiState? =
    sessions.firstOrNull { it.id == sessionId }
        ?: openedSession?.session?.takeIf { it.id == sessionId }

internal fun EntryStateHolder.mutationSessionFor(gateway: SessionGatewayPort): SessionListUiState? =
    mutableUiState.value.sessionList
        ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }

internal fun EntryStateHolder.rememberSessionRuns(
    sessionId: SessionId,
    openedSession: OpenedSession,
): List<Run> {
    authoritativeSessionHistoryGenerations[sessionId] =
        (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) + 1
    val incomingRuns = openedSession.history.runs()
    val incomingRunIds = incomingRuns.mapTo(mutableSetOf()) { it.id }
    authoritativeSessionRuns[sessionId] =
        authoritativeSessionRuns[sessionId]
            .orEmpty()
            .filter { it.id !in incomingRunIds } + incomingRuns
    val localRunIds = sessionRuns[sessionId].orEmpty().mapTo(mutableSetOf()) { it.id }
    val retainedLocalRuns =
        sessionRuns[sessionId].orEmpty() +
            allObservationStates(sessionId)
                .filter { state -> state.run.id in localRunIds }
                .map(RunObservationState::run)
    sessionRuns[sessionId] = mergeRuns(emptyList(), retainedLocalRuns)
    return visibleSessionRuns(sessionId)
}

internal fun EntryStateHolder.beginSessionMutation(
    sessionId: SessionId,
    gateway: SessionGatewayPort,
): SessionMutationRequest {
    val request =
        SessionMutationRequest(
            sessionId = sessionId,
            gateway = gateway,
            connectionGeneration = connectionGeneration,
            attemptId = ++nextMutationAttemptId,
        )
    mutationOwners[sessionId] = request
    return request
}

internal fun EntryStateHolder.isCurrentSessionMutation(request: SessionMutationRequest): Boolean {
    val current = mutationOwners[request.sessionId]
    return current?.attemptId == request.attemptId &&
        connectionGeneration == request.connectionGeneration &&
        sessionGateway === request.gateway
}

internal fun EntryStateHolder.createMutationJob(
    request: SessionMutationRequest,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    lateinit var job: Job
    job =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(sessionRequestLock) {
                    if (mutationJobs[request.sessionId] === job) {
                        mutationJobs.remove(request.sessionId)
                    }
                    if (mutationOwners[request.sessionId]?.attemptId == request.attemptId) {
                        mutationOwners.remove(request.sessionId)
                    }
                }
            }
        }
    synchronized(sessionRequestLock) {
        mutationJobs[request.sessionId] = job
    }
    return job
}

internal fun EntryStateHolder.createRunJob(
    sessionId: SessionId,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    lateinit var job: Job
    job =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(sessionRequestLock) {
                    if (runJobs[sessionId] === job) {
                        runJobs.remove(sessionId)
                        val current = mutableUiState.value.sessionList
                        val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
                        if (current != null && opened?.isSending == true) {
                            mutableUiState.value =
                                mutableUiState.value.copy(
                                    sessionList = current.copy(openedSession = opened.copy(isSending = false)),
                                )
                        }
                    }
                }
            }
        }
    synchronized(sessionRequestLock) {
        runJobs[sessionId] = job
    }
    return job
}

internal fun EntryStateHolder.applyConfirmedSession(
    session: Session,
    request: SessionMutationRequest,
) {
    synchronized(sessionRequestLock) {
        if (!isCurrentSessionMutation(request)) return
        val current =
            mutableUiState.value.sessionList?.takeIf { it.sessionForMutation(session.id) != null } ?: return
        val updated = session.toSessionItemUiState()
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessions =
                            current.sessions
                                .map { item -> if (item.id == session.id) updated else item }
                                .orderedSessions(),
                        openedSession =
                            current.openedSession
                                ?.takeIf { it.session.id == session.id }
                                ?.copy(session = updated)
                                ?: current.openedSession,
                        sessionMutations = current.sessionMutations - session.id,
                    ),
            )
        unresolvedSessionMutations.remove(recoverySessionKey(session.id))
    }
}

internal fun EntryStateHolder.mutationMapAfter(
    sessionId: SessionId,
    mutation: SessionMutationUiState,
    mutations: Map<SessionId, SessionMutationUiState>,
): Map<SessionId, SessionMutationUiState> {
    return if (mutation.hasNoPendingWork()) mutations - sessionId else mutations + (sessionId to mutation)
}
