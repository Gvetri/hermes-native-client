package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId

internal fun EntryStateHolder.recoverySessionKey(
    sessionId: SessionId,
    endpoint: String = mutableUiState.value.endpoint,
): RecoverySessionKey = RecoverySessionKey(endpoint, sessionId)

internal fun EntryStateHolder.rememberUnresolvedSessionMutations(endpoint: String) {
    if (endpoint.isBlank()) return
    mutableUiState.value.sessionList?.sessionMutations?.forEach { (sessionId, mutation) ->
        val action = mutation.pendingAction ?: return@forEach
        unresolvedSessionMutations[RecoverySessionKey(endpoint, sessionId)] =
            mutation.copy(
                rename =
                    mutation.rename?.copy(
                        isSubmitting = false,
                        errorCategory = SessionRenameErrorCategory.GATEWAY_REQUEST_FAILED,
                    ),
                delete = mutation.delete?.copy(isSubmitting = false),
                pendingAction = null,
                errorCategory = SessionMutationErrorCategory.GATEWAY_REQUEST_FAILED,
                retryAction = action,
            )
    }
}

internal fun EntryStateHolder.restoreUnresolvedSessionMutations(
    mutations: Map<SessionId, SessionMutationUiState>,
): Map<SessionId, SessionMutationUiState> {
    val endpoint = mutableUiState.value.endpoint
    return mutations +
        unresolvedSessionMutations
            .filterKeys { it.endpoint == endpoint }
            .mapKeys { (key, _) -> key.sessionId }
}

internal fun EntryStateHolder.retainSessionMutation(
    mutations: Map<SessionId, SessionMutationUiState>,
    sessionId: SessionId,
): Map<SessionId, SessionMutationUiState> {
    val retained = retainRenameDraft(mutations, sessionId)
    return unresolvedSessionMutations[recoverySessionKey(sessionId)]?.let { mutation ->
        retained + (sessionId to mutation)
    } ?: retained
}

internal fun EntryStateHolder.pendingRunSubmissionKey(
    sessionId: SessionId,
    endpoint: String = mutableUiState.value.endpoint,
): PendingRunSubmissionKey = PendingRunSubmissionKey(endpoint, sessionId)

internal fun EntryStateHolder.forgetPendingRecoveryEntry(
    endpoint: String?,
    entry: RunRecoveryEntry,
) {
    if (endpoint == null) return
    pendingDisconnectedRecoveryEntries[endpoint]?.remove(entry)
    if (pendingDisconnectedRecoveryEntries[endpoint].isNullOrEmpty()) {
        pendingDisconnectedRecoveryEntries.remove(endpoint)
    }
}

internal fun EntryStateHolder.forgetUnresolvedLocalRun(
    sessionId: SessionId,
    runId: RunId,
) {
    val key = recoverySessionKey(sessionId)
    unresolvedLocalRunIds[key]?.remove(runId)
    unresolvedLocalRunAttempts[key]?.remove(runId)
    if (unresolvedLocalRunIds[key].isNullOrEmpty()) {
        unresolvedLocalRunIds.remove(key)
    }
    if (unresolvedLocalRunAttempts[key].isNullOrEmpty()) {
        unresolvedLocalRunAttempts.remove(key)
    }
}

internal fun EntryStateHolder.rememberUnresolvedLocalRun(
    endpoint: String,
    sessionId: SessionId,
    runId: RunId,
    attemptId: String,
) {
    val key = RecoverySessionKey(endpoint, sessionId)
    unresolvedLocalRunIds.getOrPut(key, ::mutableSetOf).add(runId)
    unresolvedLocalRunAttempts.getOrPut(key, ::mutableMapOf)[runId] = attemptId
}

internal fun EntryStateHolder.markPendingCreateStarted(
    key: RecoverySessionKey,
    pendingCreate: PendingCreateState,
    expectedConnectionGeneration: Long,
): Boolean =
    synchronized(sessionRequestLock) {
        if (
            connectionGeneration != expectedConnectionGeneration ||
            pendingCreateSessions[key] != pendingCreate
        ) {
            false
        } else {
            pendingCreateStarted += key
            true
        }
    }

internal fun EntryStateHolder.forgetPendingCreate(
    key: RecoverySessionKey,
    pendingCreate: PendingCreateState,
) {
    synchronized(sessionRequestLock) {
        if (pendingCreateSessions[key] == pendingCreate) {
            pendingCreateSessions.remove(key)
            pendingCreateStarted.remove(key)
        }
    }
}
