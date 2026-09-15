package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId

class SubmitMessage(
    private val gateway: RunGatewayPort,
) {
    fun execute(
        sessionId: SessionId,
        input: String,
    ): Run {
        val run = gateway.createRun(sessionId, input)
        if (run.sessionId != sessionId) {
            throw GatewayException(
                GatewayErrorCategory.INVALID_RESPONSE,
                "Invalid Gateway response while creating a Run: session_id does not match the requested Session.",
            )
        }
        return run
    }
}
