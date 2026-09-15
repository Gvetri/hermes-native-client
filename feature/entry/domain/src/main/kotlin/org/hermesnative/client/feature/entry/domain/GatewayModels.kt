package org.hermesnative.client.feature.entry.domain

import java.util.Locale

@JvmInline
value class SessionId(
    val value: String,
)

@JvmInline
value class RunId(
    val value: String,
)

data class GatewayConnection(
    val endpoint: String,
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
    val id: String,
    val role: String?,
    val content: String?,
    val runId: RunId? = null,
    val runStatus: String? = null,
    val runResult: String? = null,
    val timestamp: String? = null,
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

private val terminalRunStatuses =
    setOf(
        "completed",
        "succeeded",
        "failed",
        "error",
        "cancelled",
        "canceled",
    )

fun Run.isActive(): Boolean = status.trim().lowercase(Locale.ROOT) !in terminalRunStatuses

data class RunSubmissionState(
    val latestRun: Run? = null,
    val activeRuns: List<Run> = emptyList(),
    val isSubmissionPending: Boolean = false,
) {
    val canSubmit: Boolean
        get() =
            !isSubmissionPending &&
                activeRuns.none(Run::isActive) &&
                latestRun?.isActive() != true
}

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
