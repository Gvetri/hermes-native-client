package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Job
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry

internal fun EntryStateHolder.clearRecoveryState() {
    connectionRecoveryJobs.clear()
    connectionRecoverySessionCounts.clear()
    connectionRecoveryFailedSessions.clear()
    recoverySessionCounts.clear()
    recoveryClaims.clear()
    recoveryHandledEntries.clear()
}

internal fun EntryStateHolder.clearSessionDraftsAndRuns() {
    sessionDrafts.clear()
    sessionDraftRevisions.clear()
    sessionSendErrors.clear()
    pendingRunDrafts.clear()
    sessionRuns.clear()
    submittedRunInputs.clear()
    authoritativeSessionHistoryGenerations.clear()
    authoritativeSessionRuns.clear()
}

internal fun EntryStateHolder.collectAndClearRequestJobs(): List<Job> =
    (mutationJobs.values + runJobs.values + runObservationJobs.values).toList().also {
        mutationJobs.clear()
        runJobs.clear()
        runObservationJobs.clear()
        runObservations.clear()
        runObservationRunIds.clear()
        pendingRunObservationRequests.clear()
        runObservationStates.clear()
        unresolvedSubmissionSessions.clear()
        ambiguousSubmissionSessions.clear()
        recoveryUnavailableSessions.clear()
        unresolvedLocalRunIds.clear()
        unresolvedLocalRunAttempts.clear()
        uncertainSendDrafts.clear()
        uncertainSubmissionRunIds.clear()
        uncertainSubmissionAttemptIds.clear()
        pendingTimedOutSends.clear()
        reconcilingSessions.clear()
        notifiedTerminalRunIds.clear()
    }

internal fun EntryStateHolder.releaseRecoverySessionCount(
    entry: RunRecoveryEntry,
    recoveryContext: RecoveryRunContext,
) {
    val remaining = (connectionRecoverySessionCounts[entry.sessionId] ?: 1) - 1
    if (remaining > 0) {
        connectionRecoverySessionCounts[entry.sessionId] = remaining
    } else {
        connectionRecoverySessionCounts.remove(entry.sessionId)
        val recoveryFailed = connectionRecoveryFailedSessions.remove(entry.sessionId)
        if (
            recoveryContext.updateVisibleUi &&
            sessionRequestGeneration == recoveryContext.sessionGeneration
        ) {
            if (recoveryFailed) {
                markRecoveryFailure(entry.sessionId)
            } else {
                clearRecoveryUiIfIdle(entry.sessionId)
            }
        }
    }
}
