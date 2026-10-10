package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.hermesnative.client.feature.entry.application.RenameSession
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId

internal fun EntryStateHolder.showRenameSession(sessionId: SessionId) {
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList?.takeIf { it.allowsSessionMutation() } ?: return
        val mutation = current.sessionMutations[sessionId]
        val session = current.sessionForMutation(sessionId)
        if (mutation?.pendingAction != null || mutation?.delete != null || session == null) return
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessionMutations =
                            current.sessionMutations +
                                (
                                    sessionId to
                                        SessionMutationUiState(
                                            rename = SessionRenameUiState(session.title),
                                        )
                                ),
                    ),
            )
    }
}

internal fun EntryStateHolder.updateRenameSessionTitle(
    sessionId: SessionId,
    value: String,
) {
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return
        val mutation = current.sessionMutations[sessionId]?.takeIf { it.pendingAction == null }
        val rename = mutation?.rename?.takeIf { !it.isSubmitting }
        if (mutation == null || rename == null) return
        val updated =
            mutation.copy(
                rename = rename.copy(titleDraft = value, errorCategory = null),
                errorCategory = null,
                retryAction = null,
            )
        val key = recoverySessionKey(sessionId)
        if (key in unresolvedSessionMutations) {
            unresolvedSessionMutations[key] = updated
        }
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessionMutations = current.sessionMutations + (sessionId to updated),
                    ),
            )
    }
}

internal fun EntryStateHolder.cancelRenameSession(sessionId: SessionId) {
    synchronized(sessionRequestLock) {
        val current = mutableUiState.value.sessionList ?: return
        val mutation = current.sessionMutations[sessionId]?.takeIf { it.pendingAction == null } ?: return
        val remaining = mutation.copy(rename = null)
        unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessionMutations =
                            if (remaining.delete == null && remaining.errorCategory == null) {
                                current.sessionMutations - sessionId
                            } else {
                                current.sessionMutations + (sessionId to remaining)
                            },
                    ),
            )
    }
}

internal fun EntryStateHolder.confirmRenameSession(sessionId: SessionId) {
    val gateway = sessionGateway ?: return
    val job =
        synchronized(sessionRequestLock) {
            val current =
                mutableUiState.value.sessionList
                    ?.takeIf { it.allowsSessionMutation() && sessionGateway === gateway }
                    ?: return@synchronized null
            val mutation = current.sessionMutations[sessionId]
            val rename = mutation?.rename
            if (mutation == null || rename == null) return@synchronized null
            if (mutation.pendingAction != null || rename.isSubmitting) return@synchronized null
            val title = rename.titleDraft.trim()
            val validationError = rename.titleDraft.validateRenameTitle()
            if (validationError != null) {
                mutableUiState.value =
                    mutableUiState.value.copy(
                        sessionList =
                            current.copy(
                                sessionMutations =
                                    current.sessionMutations +
                                        (
                                            sessionId to
                                                mutation.copy(
                                                    rename = rename.copy(errorCategory = validationError),
                                                )
                                        ),
                            ),
                    )
                return@synchronized null
            }
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            sessionMutations =
                                current.sessionMutations +
                                    (
                                        sessionId to
                                            mutation.copy(
                                                rename =
                                                    rename.copy(
                                                        isSubmitting = true,
                                                        errorCategory = null,
                                                    ),
                                                pendingAction = SessionMutationAction.RENAME,
                                                errorCategory = null,
                                                retryAction = null,
                                            )
                                    ),
                        ),
                )
            val request = beginSessionMutation(sessionId, gateway)
            unresolvedSessionMutations.remove(recoverySessionKey(sessionId))
            submitRenameSession(gateway, sessionId, title, request)
        } ?: return
    job.start()
}

internal fun EntryStateHolder.submitRenameSession(
    gateway: SessionGatewayPort,
    sessionId: SessionId,
    title: String,
    request: SessionMutationRequest,
): Job =
    createMutationJob(request) {
        try {
            val confirmed = RenameSession(gateway).execute(sessionId, title)
            applyConfirmedSession(confirmed, request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showRenameFailure(request)
        }
    }

internal fun EntryStateHolder.showRenameFailure(request: SessionMutationRequest) {
    synchronized(sessionRequestLock) {
        if (!isCurrentSessionMutation(request)) return
        val sessionId = request.sessionId
        val current = mutableUiState.value.sessionList
        val mutation = current?.sessionMutations?.get(sessionId)
        val rename = mutation?.rename
        if (current == null || mutation == null || rename == null) return
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        sessionMutations =
                            current.sessionMutations +
                                (
                                    sessionId to
                                        mutation.copy(
                                            rename =
                                                rename.copy(
                                                    isSubmitting = false,
                                                    errorCategory =
                                                        SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                                                ),
                                            pendingAction = null,
                                            retryAction = SessionMutationAction.RENAME,
                                        )
                                ),
                    ),
            )
    }
}

internal fun unsentRenameDrafts(mutations: SessionMutationMap): SessionMutationMap {
    return mutations
        .mapNotNull { (sessionId, mutation) ->
            mutation.rename
                ?.takeIf {
                    !it.isSubmitting &&
                        mutation.pendingAction == null &&
                        mutation.errorCategory == null &&
                        mutation.retryAction == null
                }
                ?.let { rename ->
                    sessionId to
                        SessionMutationUiState(
                            rename = SessionRenameUiState(titleDraft = rename.titleDraft),
                        )
                }
        }.toMap()
}

internal fun retainRenameDrafts(
    mutations: Map<SessionId, SessionMutationUiState>,
    sessionIds: List<SessionId>,
): Map<SessionId, SessionMutationUiState> = unsentRenameDrafts(mutations).filterKeys { it in sessionIds }

internal fun retainRenameDraft(
    mutations: Map<SessionId, SessionMutationUiState>,
    sessionId: SessionId,
): Map<SessionId, SessionMutationUiState> =
    unsentRenameDrafts(mutations)
        .filterKeys { it == sessionId }
        .let { draft ->
            if (draft.isEmpty()) {
                mutations - sessionId
            } else {
                mutations + draft
            }
        }

internal fun String.validateRenameTitle(): SessionRenameErrorCategory? =
    when {
        trim().isEmpty() -> SessionRenameErrorCategory.EMPTY_TITLE
        any(Char::isISOControl) -> SessionRenameErrorCategory.CONTROL_CHARACTER
        else -> null
    }
