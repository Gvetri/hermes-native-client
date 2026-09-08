package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage

class LoadSessionList(
    private val gateway: SessionGatewayPort,
) {
    fun execute(): SessionPage = gateway.listSessions()
}

data class OpenedSession(
    val session: Session,
    val history: SessionHistory,
)

class OpenSession(
    private val gateway: SessionGatewayPort,
) {
    fun execute(sessionId: SessionId): OpenedSession {
        val session = gateway.openSession(sessionId)
        if (session.id != sessionId) {
            throw invalidResponse("session.id does not match the requested Session")
        }

        val history = gateway.loadSessionHistory(sessionId)
        if (history.sessionId != sessionId) {
            throw invalidResponse("session_id does not match the requested Session")
        }
        return OpenedSession(session, history)
    }

    private fun invalidResponse(detail: String): GatewayException =
        GatewayException(
            GatewayErrorCategory.INVALID_RESPONSE,
            "Invalid Gateway response while opening a Session: $detail.",
        )
}
