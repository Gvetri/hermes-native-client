package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId

/** Starts the one foreground observation owned by the currently visible session. */
class ObserveRun(
    private val gateway: RunGatewayPort,
) {
    fun execute(runId: RunId): RunEventObservation = gateway.observeRun(runId)

    operator fun invoke(runId: RunId): RunEventObservation = execute(runId)
}
