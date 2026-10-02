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
    val bearerCredential: String? = null,
)

/**
 * Session list request over the pinned `GET /api/sessions` contract.
 *
 * The pinned Gateway pages with `limit` and `offset` and reports `has_more`
 * (projected to [SessionPage.nextOffset]); it does not accept a cursor token.
 */
data class SessionListRequest(
    val limit: Int = 20,
    val offset: Int = 0,
)

data class Session(
    val id: SessionId,
    val title: String?,
    val preview: String?,
    val pinned: Boolean,
)

data class SessionPage(
    val sessions: List<Session>,
    val nextOffset: Int?,
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

const val UNKNOWN_RUN_STATUS = "unknown"

private val terminalRunStatuses =
    setOf(
        "completed",
        "complete",
        "succeeded",
        "success",
        "failed",
        "failure",
        "error",
        "cancelled",
        "canceled",
        "interrupted",
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
    COMPLETING,
    MESSAGE_DELTA,
    TEXT_DELTA,
    COMPLETED,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    CANCELLED,
}

data class RunEvent(
    val type: RunEventType,
    val runId: RunId,
    val status: String = "",
    val text: String? = null,
    val eventId: String? = null,
) {
    val delta: String?
        get() = text

    val content: String?
        get() = text
}
