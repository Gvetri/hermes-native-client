package org.hermesnative.client.feature.entry.domain

@JvmInline
value class SessionId(
    val value: String,
)

@JvmInline
value class RunId(
    val value: String,
)

data class SessionListRequest(
    val limit: Int = 20,
    val cursor: String? = null,
    val search: String? = null,
)

data class Session(
    val id: SessionId,
    val title: String?,
    val preview: String?,
    val pinned: Boolean,
    val updatedAt: String?,
)

data class SessionPage(
    val sessions: List<Session>,
    val nextCursor: String?,
)

data class GatewayHistoryMessage(
    val id: String?,
    val role: String?,
    val content: String?,
)

data class SessionHistory(
    val sessionId: SessionId,
    val messages: List<GatewayHistoryMessage>,
    val nextCursor: String?,
)

data class SessionPinResult(
    val sessionId: SessionId,
    val pinned: Boolean,
)

data class Run(
    val id: RunId,
    val sessionId: SessionId,
    val status: String,
)

enum class RunEventType {
    STARTED,
    RUNNING,
    COMPLETED,
}

data class RunEvent(
    val type: RunEventType,
    val runId: RunId,
    val status: String,
)
