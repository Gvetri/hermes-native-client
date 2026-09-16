package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.AuthoritativeRunReconciliation
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.decideRunReconciliation

class ReconcileRun(
    private val runGateway: RunGatewayPort,
    private val sessionGateway: SessionGatewayPort,
) {
    fun execute(
        runId: RunId,
        sessionId: SessionId,
    ): AuthoritativeRunReconciliation {
        val run = runGateway.getRunStatus(runId)
        if (run.id != runId || run.sessionId != sessionId || run.status.isBlank()) {
            throw invalidResponse("Run status identity or status does not match the requested Run")
        }

        val history = sessionGateway.loadSessionHistory(sessionId)
        if (history.sessionId != sessionId) {
            throw invalidResponse("Session history identity does not match the requested Session")
        }

        return AuthoritativeRunReconciliation(
            run = run,
            history = history,
            decision = decideRunReconciliation(run, history),
        )
    }

    private fun invalidResponse(detail: String): GatewayException =
        GatewayException(
            GatewayErrorCategory.INVALID_RESPONSE,
            "Invalid Gateway response during Run reconciliation: $detail.",
        )
}
