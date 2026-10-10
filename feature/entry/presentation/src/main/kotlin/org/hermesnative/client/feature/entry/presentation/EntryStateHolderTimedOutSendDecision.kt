package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunReconciliationDecision
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionReconciliation
import org.hermesnative.client.feature.entry.domain.decideRunReconciliation

internal fun EntryStateHolder.confirmedTerminalRunIds(
    reconciliation: SessionReconciliation,
    submissionRun: Run?,
): Set<RunId> =
    reconciliation.discoveredRuns
        .filter { run ->
            decideRunReconciliation(run) ==
                RunReconciliationDecision.CONFIRMED
        }
        .map { it.id }
        .plus(
            submissionRun
                ?.takeIf { run -> decideRunReconciliation(run) == RunReconciliationDecision.CONFIRMED }
                ?.id,
        )
        .filterNotNull()
        .toSet()

internal fun EntryStateHolder.submissionConfirmed(
    submissionRun: Run?,
    belongsToThisSubmission: Boolean,
): Boolean =
    submissionRun != null &&
        decideRunReconciliation(submissionRun) == RunReconciliationDecision.CONFIRMED &&
        belongsToThisSubmission

internal fun EntryStateHolder.recoveryStoreAllowsNoNewRun(
    uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
    belongsToThisSubmission: Boolean,
): Boolean =
    uncertaintySnapshot == null ||
        (
            belongsToThisSubmission &&
                uncertaintySnapshot.settled &&
                !uncertaintySnapshot.requiresRunMatch
        )

internal fun EntryStateHolder.noNewRunConfirmsNoSubmission(
    sessionId: SessionId,
    resolveWhenNoNewRun: Boolean,
    discoveredUnattributedRun: Boolean,
    recoveryStoreAllowsNoNewRun: Boolean,
): Boolean =
    resolveWhenNoNewRun &&
        sessionId !in ambiguousSubmissionSessions &&
        !discoveredUnattributedRun &&
        recoveryStoreAllowsNoNewRun &&
        uncertainSubmissionRunIds[sessionId] == null

internal fun EntryStateHolder.uncertaintyCanBeRemoved(
    pendingKey: PendingRunSubmissionKey,
    uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
    canResolveUncertainty: Boolean,
): Boolean =
    !canResolveUncertainty ||
        !runSubmissionUncertaintyStore.contains(pendingKey) ||
        runCatching {
            uncertaintySnapshot != null &&
                runSubmissionUncertaintyStore.removeIfSnapshotMatches(pendingKey, uncertaintySnapshot)
        }.getOrDefault(false)

internal fun EntryStateHolder.uncertaintyIdentityIsStable(
    pendingKey: PendingRunSubmissionKey,
    uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
    canClearUncertainty: Boolean,
    belongsToThisSubmission: Boolean,
): Boolean {
    val currentSnapshot = runSubmissionUncertaintyStore.snapshot(pendingKey)
    val stable =
        if (canClearUncertainty) {
            currentSnapshot == null
        } else if (uncertaintySnapshot == null) {
            currentSnapshot == null
        } else {
            currentSnapshot?.let { current ->
                current.attemptId == uncertaintySnapshot.attemptId &&
                    current.knownRunIds == uncertaintySnapshot.knownRunIds &&
                    current.boundRunId == uncertaintySnapshot.boundRunId
            } ?: false
        }
    return belongsToThisSubmission && stable
}
