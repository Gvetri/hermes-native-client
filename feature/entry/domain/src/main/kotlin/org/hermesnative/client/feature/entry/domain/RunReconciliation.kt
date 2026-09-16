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
    if (!run.isActive() && history.containsTerminalRun(run)) {
        RunReconciliationDecision.CONFIRMED
    } else {
        RunReconciliationDecision.UNCERTAIN
    }

fun SessionHistory.containsTerminalRun(run: Run): Boolean {
    val expectedState = run.toRunPresentationState()
    return expectedState.isTerminal() &&
        messages.any { message ->
            message.runId == run.id &&
                message.runStatus?.toRunPresentationState() == expectedState
        }
}

fun SessionHistory.runs(): List<Run> {
    val runsById = linkedMapOf<RunId, Run>()
    messages.forEach { message ->
        val runId = message.runId
        val explicitStatus = message.runStatus?.takeIf(String::isNotBlank)
        if (runId != null) {
            val knownStatus = explicitStatus ?: runsById[runId]?.status ?: UNKNOWN_RUN_STATUS
            runsById.remove(runId)
            runsById[runId] = Run(runId, sessionId, knownStatus)
        }
    }
    return runsById.values.toList()
}

data class SessionReconciliation(
    val history: SessionHistory,
    val discoveredRuns: List<Run>,
)
