package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import org.hermesnative.client.feature.entry.application.PinSession
import org.hermesnative.client.feature.entry.application.UnpinSession
import org.hermesnative.client.feature.entry.domain.SessionId

internal fun EntryStateHolder.pinSession(sessionId: SessionId) {
    changeSessionPin(sessionId, pinned = true)
}

internal fun EntryStateHolder.unpinSession(sessionId: SessionId) {
    changeSessionPin(sessionId, pinned = false)
}

internal fun EntryStateHolder.changeSessionPin(
    sessionId: SessionId,
    pinned: Boolean,
) {
    val gateway = sessionGateway ?: return
    val action = if (pinned) SessionMutationAction.PIN else SessionMutationAction.UNPIN
    val job =
        synchronized(sessionRequestLock) {
            val current =
                mutableUiState.value.sessionList
                    ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }
                    ?: return@synchronized null
            val mutation = current.sessionMutations[sessionId] ?: SessionMutationUiState()
            val blocked =
                current.sessionForMutation(sessionId) == null ||
                    mutation.pendingAction != null ||
                    mutation.rename != null ||
                    mutation.delete != null
            if (blocked) return@synchronized null
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                pendingAction = action,
                                                errorCategory = null,
                                                retryAction = null,
                                            )
                                    ),
                        ),
                )
            val request = beginSessionMutation(sessionId, gateway)
            unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
            createMutationJob(request) {
                try {
                    val result =
                        if (pinned) {
                            PinSession(gateway).execute(sessionId)
                        } else {
                            UnpinSession(gateway).execute(sessionId)
                        }
                    applyConfirmedPin(result.sessionId, result.pinned, request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showPinFailure(request, action)
                }
            }
        } ?: return
    job.start()
}

internal fun EntryStateHolder.applyConfirmedPin(
    sessionId: SessionId,
    pinned: Boolean,
    request: SessionMutationRequest,
) {
    val refreshJob =
        synchronized(sessionRequestLock) {
            if (!isCurrentSessionMutation(request)) return@synchronized null
            val current = mutableUiState.value.sessionList ?: return@synchronized null
            if (current.sessionForMutation(sessionId) == null) return@synchronized null
            val mutation = current.sessionMutations[sessionId] ?: return@synchronized null
            val updatedSessions =
                current.sessions
                    .map { item -> if (item.id == sessionId) item.copy(pinned = pinned) else item }
                    .orderedSessions()
            val remainingMutation =
                mutation.copy(
                    pendingAction = null,
                    errorCategory = null,
                    retryAction = null,
                )
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            sessions = updatedSessions,
                            openedSession =
                                current.openedSession?.let { opened ->
                                    if (opened.session.id == sessionId) {
                                        opened.copy(session = opened.session.copy(pinned = pinned))
                                    } else {
                                        opened
                                    }
                                },
                            sessionMutations =
                                mutationMapAfter(sessionId, remainingMutation, current.sessionMutations),
                        ),
                )
            unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
            sessionGateway?.let { gateway ->
                createFirstPageLoadJob(
                    gateway = gateway,
                    query = current.searchQuery,
                    isRefreshing = true,
                    isSearching = false,
                    preserveSessions = true,
                )
            }
        }
    refreshJob?.start()
}

internal fun EntryStateHolder.showPinFailure(
    request: SessionMutationRequest,
    action: SessionMutationAction,
) {
    synchronized(sessionRequestLock) {
        if (!isCurrentSessionMutation(request)) return
        val sessionId = request.sessionId
        val current = mutableUiState.value.sessionList
        val mutation = current?.sessionMutations?.get(sessionId)
        if (current == null || mutation == null) return
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessionMutations =
                            current.sessionMutations +
                                (
                                    sessionId to
                                        mutation.copy(
                                            pendingAction = null,
                                            errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                                            retryAction = action,
                                        )
                                ),
                    ),
            )
    }
}
