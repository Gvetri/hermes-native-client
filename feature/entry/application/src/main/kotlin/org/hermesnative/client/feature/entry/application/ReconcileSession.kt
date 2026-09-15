package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionReconciliation

class ReconcileSession(
    private val sessionGateway: SessionGatewayPort,
    private val runGateway: RunGatewayPort,
) {
    fun execute(
        sessionId: SessionId,
        knownRunIds: Set<RunId> = emptySet(),
    ): SessionReconciliation {
        val history = sessionGateway.loadSessionHistory(sessionId)
        if (history.sessionId != sessionId) {
            throw invalidResponse("Session history identity does not match the requested Session")
        }

        val discoveredRuns =
            history.messages
                .mapNotNull { it.runId }
                .distinct()
                .filterNot(knownRunIds::contains)
                .map { runId ->
                    runGateway.getRunStatus(runId).also { run ->
                        if (run.id != runId || run.sessionId != sessionId || run.status.isBlank()) {
                            throw invalidResponse("Run status identity or status does not match the requested Run")
                        }
                    }
                }
        return SessionReconciliation(history, discoveredRuns)
    }

    private fun invalidResponse(detail: String): GatewayException =
        GatewayException(
            GatewayErrorCategory.INVALID_RESPONSE,
            "Invalid Gateway response during Session reconciliation: $detail.",
        )
}
