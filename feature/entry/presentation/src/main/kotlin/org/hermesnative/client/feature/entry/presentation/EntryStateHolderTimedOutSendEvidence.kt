package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionReconciliation
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.runs

internal fun EntryStateHolder.authoritativeRunsFor(reconciliation: SessionReconciliation): List<Run> =
    mergeRuns(reconciliation.history.runs(), reconciliation.discoveredRuns)

internal fun EntryStateHolder.retainedAuthoritativeRuns(
    sessionId: SessionId,
    incoming: List<Run>,
): List<Run> {
    val incomingRunIds = incoming.mapTo(mutableSetOf()) { it.id }
    return authoritativeSessionRuns[sessionId]
        .orEmpty()
        .filter { existing -> existing.id !in incomingRunIds }
}

internal fun EntryStateHolder.uncertaintyBelongsToSubmission(
    uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
    pendingRecovery: TimedOutSendRecovery?,
    attemptId: String,
): Boolean =
    uncertaintySnapshot == null ||
        (
            uncertaintySnapshot.attemptId == attemptId &&
                uncertaintySnapshot.knownRunIds == pendingRecovery?.knownRunIds.orEmpty()
        )

internal fun EntryStateHolder.localBoundRunIdFor(
    sessionId: SessionId,
    attemptId: String,
    uncertaintySnapshot: RunSubmissionUncertaintySnapshot?,
): RunId? =
    uncertainSubmissionRunIds[sessionId]
        ?.takeIf {
            uncertaintySnapshot == null && uncertainSubmissionAttemptIds[sessionId] == attemptId
        }

internal fun EntryStateHolder.boundSubmissionRun(
    sessionId: SessionId,
    durableBoundRunId: RunId?,
    localBoundRunId: RunId?,
    reconciliation: SessionReconciliation,
): Run? =
    (durableBoundRunId ?: localBoundRunId)?.let { runId ->
        reconciliation.discoveredRuns.lastOrNull { it.id == runId }
            ?: visibleSessionRuns(sessionId).lastOrNull { it.id == runId && it.isActive() }
    }

internal fun EntryStateHolder.discoveredUnattributedRun(
    boundSubmissionRun: Run?,
    reconciliation: SessionReconciliation,
    belongsToThisSubmission: Boolean,
): Boolean =
    boundSubmissionRun == null &&
        reconciliation.discoveredRuns.isNotEmpty() &&
        belongsToThisSubmission
