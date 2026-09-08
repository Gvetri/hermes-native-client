package org.hermesnative.client.feature.entry.domain

interface GatewayCapabilityPort {
    fun discoverCapabilities(): GatewayCapabilities
}

interface SessionGatewayPort {
    fun listSessions(request: SessionListRequest): SessionPage

    fun listSessions(): SessionPage = listSessions(SessionListRequest())

    fun createSession(title: String?): Session

    fun openSession(sessionId: SessionId): Session

    fun loadSessionHistory(sessionId: SessionId): SessionHistory

    fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session

    fun deleteSession(sessionId: SessionId)

    fun pinSession(sessionId: SessionId): SessionPinResult

    fun unpinSession(sessionId: SessionId): SessionPinResult
}

interface RunGatewayPort {
    fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run

    fun getRunStatus(runId: RunId): Run

    fun observeRun(runId: RunId): Sequence<RunEvent>
}

interface GatewayContractPort : GatewayCapabilityPort, SessionGatewayPort, RunGatewayPort
