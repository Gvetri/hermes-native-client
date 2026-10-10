package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId

internal fun EntryStateHolder.loadInitialSessions(gateway: SessionGatewayPort) {
    val sessionList = mutableUiState.value.sessionList ?: return
    createFirstPageLoadJob(
        gateway = gateway,
        query = sessionList.searchQuery,
        isRefreshing = false,
        isSearching = false,
        preserveSessions = false,
    )?.start()
}

internal fun EntryStateHolder.flushPendingRecoveryEntries(endpoint: String) {
    val entries =
        synchronized(sessionRequestLock) {
            pendingDisconnectedRecoveryEntries.remove(endpoint).orEmpty().toList()
        }
    entries.forEach { entry -> persistOrQueueRecoveryEntry(endpoint, entry) }
}

internal fun EntryStateHolder.persistOrQueueRecoveryEntry(
    endpoint: String,
    entry: RunRecoveryEntry,
): Boolean {
    val persistence =
        synchronized(sessionRequestLock) {
            persistRunRecoveryEntry to runRecoveryRegistry
        }
    if (persistence.first == null && persistence.second == null) return true
    return try {
        synchronized(recoveryPersistenceLock) {
            persistence.first?.invoke(endpoint, entry)
                ?: persistence.second?.save(entry)
                ?: error("No endpoint-scoped recovery writer is configured.")
        }
        true
    } catch (_: Exception) {
        synchronized(sessionRequestLock) {
            pendingDisconnectedRecoveryEntries
                .getOrPut(endpoint, ::mutableSetOf)
                .add(entry)
        }
        false
    }
}

internal fun EntryStateHolder.removeRecoveryEntry(
    endpoint: String?,
    entry: RunRecoveryEntry,
): Boolean {
    return try {
        synchronized(recoveryPersistenceLock) {
            if (endpoint != null) {
                removeRunRecoveryEntry?.invoke(endpoint, entry) ?: runRecoveryRegistry?.remove(entry)
            } else {
                runRecoveryRegistry?.remove(entry)
            }
        }
        synchronized(sessionRequestLock) {
            forgetPendingRecoveryEntry(endpoint, entry)
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun EntryStateHolder.beginRecoveryLoad(expectedConnectionGeneration: Long): Boolean =
    synchronized(sessionRequestLock) {
        if (connectionGeneration != expectedConnectionGeneration) {
            false
        } else {
            recoveryLoadPending = true
            recoveryLoadFailed = false
            val current = mutableUiState.value.sessionList
            val opened = current?.openedSession
            if (current != null && opened != null) {
                mutableUiState.value =
                    mutableUiState.value.copy(
                        sessionList =
                            current.copy(
                                openedSession =
                                    opened.copy(
                                        isRefreshing = true,
                                        isReconciliationInProgress = true,
                                        hasUnresolvedSubmission = true,
                                    ),
                            ),
                    )
            }
            true
        }
    }

internal fun EntryStateHolder.finishRecoveryLoad(
    expectedConnectionGeneration: Long,
    entries: List<RunRecoveryEntry>,
    failed: Boolean,
) {
    synchronized(sessionRequestLock) {
        if (connectionGeneration != expectedConnectionGeneration) return
        recoveryLoadPending = false
        recoveryLoadFailed = failed
        val recoveredSessionIds = entries.mapTo(mutableSetOf()) { it.sessionId }
        val current = mutableUiState.value.sessionList
        if (failed) {
            current?.sessions?.forEach { item -> recoveryUnavailableSessions += item.id }
            current?.openedSession?.session?.id?.let(recoveryUnavailableSessions::add)
        }
        val opened = current?.openedSession
        if (current != null && opened != null) {
            val sessionId = opened.session.id
            val recoveryInProgress =
                sessionId in recoveredSessionIds ||
                    sessionId in connectionRecoverySessionCounts ||
                    sessionId in recoverySessionCounts ||
                    sessionId in reconcilingSessions
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    isRefreshing = if (failed) false else recoveryInProgress,
                                    isReconciliationInProgress = if (failed) false else recoveryInProgress,
                                    hasUnresolvedSubmission =
                                        failed || hasUnresolvedSubmission(sessionId),
                                    sendErrorCategory =
                                        if (failed) {
                                            MessageSendErrorCategory.UNCERTAIN
                                        } else {
                                            sendErrorCategoryFor(sessionId)
                                        },
                                ),
                        ),
                )
        }
    }
}

internal fun EntryStateHolder.markRecoveryFailure(sessionId: SessionId) {
    synchronized(sessionRequestLock) {
        recoveryUnavailableSessions += sessionId
        val current = mutableUiState.value.sessionList
        val opened = current?.openedSession?.takeIf { it.session.id == sessionId }
        if (current != null && opened != null) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        current.copy(
                            openedSession =
                                opened.copy(
                                    isRefreshing = false,
                                    isReconciliationInProgress = false,
                                    hasUnresolvedSubmission = true,
                                    sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                ),
                        ),
                )
        }
    }
}

internal fun EntryStateHolder.hasPendingRecoveryWork(sessionId: SessionId): Boolean =
    sessionId in connectionRecoverySessionCounts ||
        sessionId in recoverySessionCounts ||
        sessionId in reconcilingSessions ||
        recoveryLoadPending

internal fun EntryStateHolder.openedSessionFor(sessionId: SessionId): Pair<SessionListUiState, OpenSessionUiState>? =
    mutableUiState.value.sessionList?.let { current ->
        current.openedSession?.takeIf { it.session.id == sessionId }?.let { opened -> current to opened }
    }

internal fun EntryStateHolder.clearRecoveryUiIfIdle(sessionId: SessionId) {
    if (hasPendingRecoveryWork(sessionId)) return
    val (current, opened) = openedSessionFor(sessionId) ?: return
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.copy(
                            isRefreshing = false,
                            isReconciliationInProgress = false,
                            hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                            sendErrorCategory = sendErrorCategoryFor(sessionId),
                        ),
                ),
        )
}
