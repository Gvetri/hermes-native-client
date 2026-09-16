package org.hermesnative.client.feature.entry.domain

enum class RunReconciliationDecision {
    CONFIRMED,
    UNCERTAIN,
}

data class AuthoritativeRunReconciliation(
    val run: Run,
    val history: SessionHistory,
    val decision: RunReconciliationDecision,
)

fun decideRunReconciliation(
    run: Run,
    history: SessionHistory,
): RunReconciliationDecision =
    if (!run.isActive() && history.containsRun(run.id)) {
        RunReconciliationDecision.CONFIRMED
    } else {
        RunReconciliationDecision.UNCERTAIN
    }

fun SessionHistory.containsRun(runId: RunId): Boolean = messages.any { it.runId == runId }

fun SessionHistory.runs(): List<Run> {
    val runsById = linkedMapOf<RunId, Run>()
    messages.forEach { message ->
        val runId = message.runId
        val status = message.runStatus
        if (runId != null) {
            runsById.remove(runId)
            runsById[runId] = Run(runId, sessionId, status?.takeIf(String::isNotBlank) ?: UNKNOWN_RUN_STATUS)
        }
    }
    return runsById.values.toList()
}

data class SessionReconciliation(
    val history: SessionHistory,
    val discoveredRuns: List<Run>,
)
