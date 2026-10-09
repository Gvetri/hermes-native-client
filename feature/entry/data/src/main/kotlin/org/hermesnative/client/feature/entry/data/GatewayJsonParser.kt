package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hermesnative.client.feature.entry.domain.GatewayEndpoint
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionPage

internal object GatewayJsonParser {
    private val json = Json

    private const val SESSION_OBJECT = "hermes.session"
    private const val SESSION_DELETED_OBJECT = "hermes.session.deleted"
    private const val RUN_OBJECT = "hermes.run"
    private const val LIST_OBJECT = "list"

    fun parseObject(
        operation: String,
        content: String,
    ): JsonObject {
        val element =
            try {
                json.parseToJsonElement(content)
            } catch (_: Exception) {
                invalid(operation, "the response is not valid JSON")
            }
        return element as? JsonObject ?: invalid(operation, "the response must be a JSON object")
    }

    fun parseCapabilities(
        operation: String,
        root: JsonObject,
    ): Map<String, GatewayEndpoint> {
        val endpoints = requiredObject(root, "endpoints", operation)
        return endpoints.mapValues { (name, value) ->
            val endpoint = value as? JsonObject ?: invalid(operation, "field 'endpoints.$name' must be an object")
            GatewayEndpoint(
                method = requiredNonBlankString(endpoint, "method", operation),
                path = requiredNonBlankString(endpoint, "path", operation),
            )
        }
    }

    fun parseSessionPage(
        operation: String,
        root: JsonObject,
    ): SessionPage {
        requireObjectField(root, "object", LIST_OBJECT, operation)
        val limit = requiredInt(root, "limit", operation)
        val offset = requiredInt(root, "offset", operation)
        if (limit <= 0) invalid(operation, "field 'limit' must be positive")
        if (offset < 0) invalid(operation, "field 'offset' must be non-negative")
        val sessions =
            requiredArray(root, "data", operation).mapIndexed { index, value ->
                parseSession(value, "$operation.data[$index]")
            }
        val hasMore = requiredBoolean(root, "has_more", operation)
        return SessionPage(
            sessions = sessions,
            nextOffset = if (hasMore) offset + limit else null,
        )
    }

    fun parseSessionResponse(
        operation: String,
        root: JsonObject,
    ): Session {
        requireObjectField(root, "object", SESSION_OBJECT, operation)
        val session = requiredObject(root, "session", operation)
        return parseSession(session, "$operation.session")
    }

    fun parseHistory(
        operation: String,
        root: JsonObject,
    ): SessionHistory {
        requireObjectField(root, "object", LIST_OBJECT, operation)
        requiredObject(root, "pagination", operation)
        val messageIds = mutableSetOf<String>()
        val messages =
            requiredArray(root, "data", operation).mapIndexed { index, value ->
                parseMessage(value, "$operation.data[$index]").also { message ->
                    if (!messageIds.add(message.id)) {
                        invalid(operation, "field 'data[$index].id' duplicates another message ID")
                    }
                }
            }
        return SessionHistory(
            sessionId = SessionId(requiredNonBlankString(root, "session_id", operation)),
            messages = messages,
        )
    }

    fun parseDeletedSession(
        operation: String,
        root: JsonObject,
        expectedSessionId: SessionId,
    ) {
        requireObjectField(root, "object", SESSION_DELETED_OBJECT, operation)
        val deletedId = requiredNonBlankString(root, "id", operation)
        if (deletedId != expectedSessionId.value) {
            invalid(operation, "field 'id' does not match the requested Session")
        }
        if (!requiredBoolean(root, "deleted", operation)) {
            invalid(operation, "field 'deleted' must confirm the Session deletion")
        }
    }

    /**
     * Parses the pinned `POST /v1/runs` admission response `{run_id, status, replayed}`.
     *
     * The pinned response carries no `session_id`; the caller correlates the acknowledged
     * Run with the Session it declared in the request body.
     */
    fun parseRunAdmission(
        operation: String,
        root: JsonObject,
    ): RunAdmission {
        val runId = RunId(requiredNonBlankString(root, "run_id", operation))
        val status = requiredNonBlankString(root, "status", operation)
        if (root.containsKey("replayed")) {
            requiredBoolean(root, "replayed", operation)
        }
        return RunAdmission(runId = runId, status = status)
    }

    fun parseRunStatus(
        operation: String,
        root: JsonObject,
    ): Run {
        requireObjectField(root, "object", RUN_OBJECT, operation)
        return Run(
            id = RunId(requiredNonBlankString(root, "run_id", operation)),
            sessionId = SessionId(requiredNonBlankString(root, "session_id", operation)),
            status = requiredNonBlankString(root, "status", operation),
        )
    }

    fun parseRunEvent(
        operation: String,
        frame: GatewaySseFrame,
    ): RunEvent? {
        val root = parseObject(operation, frame.data)
        val eventName = requiredNonBlankString(root, "event", operation)
        val eventType = eventTypeFor(eventName) ?: return null
        val runId = RunId(requiredNonBlankString(root, "run_id", operation))
        val status =
            optionalString(root, "status", operation)
                ?: when (eventType) {
                    RunEventType.RUNNING, RunEventType.MESSAGE_DELTA -> "running"
                    RunEventType.COMPLETING -> "completing"
                    RunEventType.COMPLETED -> "succeeded"
                    RunEventType.FAILED -> "failed"
                    RunEventType.CANCELLED -> "cancelled"
                    RunEventType.STARTED,
                    RunEventType.TEXT_DELTA,
                    RunEventType.SUCCEEDED,
                    RunEventType.INTERRUPTED,
                    -> ""
                }
        val text =
            if (eventType == RunEventType.MESSAGE_DELTA) {
                optionalString(root, "delta", operation)
                    ?: invalid("$operation.$eventName", "missing required field 'delta'")
            } else {
                null
            }
        return RunEvent(
            type = eventType,
            runId = runId,
            status = status,
            text = text,
        )
    }

    private fun eventTypeFor(eventName: String): RunEventType? =
        when (eventName) {
            "message.delta" -> RunEventType.MESSAGE_DELTA
            "run.completed" -> RunEventType.COMPLETED
            "run.failed" -> RunEventType.FAILED
            "run.cancelled" -> RunEventType.CANCELLED
            "run.stopping" -> RunEventType.COMPLETING
            "tool.started", "tool.completed", "reasoning.available", "subagent.start",
            "subagent.complete", "approval.request", "approval.responded", "run.steered",
            -> RunEventType.RUNNING
            else -> null
        }

    private fun parseSession(
        value: JsonElement,
        operation: String,
    ): Session {
        val session = value as? JsonObject ?: invalid(operation, "session must be a JSON object")
        return Session(
            id = SessionId(requiredNonBlankString(session, "id", operation)),
            title = requiredNullableString(session, "title", operation),
            preview = optionalString(session, "preview", operation),
            pinned = requiredBoolean(session, "pinned", operation),
        )
    }

    private fun parseMessage(
        value: JsonElement,
        operation: String,
    ): GatewayHistoryMessage {
        val message = value as? JsonObject ?: invalid(operation, "message must be a JSON object")
        return GatewayHistoryMessage(
            id = messageIdentity(message, operation),
            role = optionalString(message, "role", operation),
            content = optionalString(message, "content", operation),
            runId = optionalString(message, "run_id", operation)?.takeIf(String::isNotBlank)?.let(::RunId),
            runStatus = optionalString(message, "run_status", operation),
            runResult = optionalString(message, "run_result", operation),
            timestamp = messageTimestamp(message, operation),
        )
    }

    private fun messageIdentity(
        message: JsonObject,
        operation: String,
    ): String {
        val value = requiredValue(message, "id", operation)
        if (value !is JsonPrimitive || value == JsonNull) invalid(operation, "invalid message identity")
        if (value.isString) return requiredNonBlankString(message, "id", operation)
        if (value.content.toLongOrNull() == null) invalid(operation, "message identity must be an integer or string")
        return value.content
    }

    private fun messageTimestamp(
        message: JsonObject,
        operation: String,
    ): String? {
        val value = message["timestamp"]
        if (value == null || value == JsonNull) return null
        if (value !is JsonPrimitive) invalid(operation, "invalid message timestamp")
        if (!value.isString && value.content.toDoubleOrNull()?.isFinite() != true) {
            invalid(operation, "message timestamp must be finite seconds or a string")
        }
        return value.content
    }

    private fun requireObjectField(
        root: JsonObject,
        path: String,
        expected: String,
        operation: String,
    ) {
        val actual = requiredNonBlankString(root, path, operation)
        if (actual != expected) {
            invalid(operation, "field '$path' must be '$expected'")
        }
    }

    private fun requiredInt(
        root: JsonObject,
        path: String,
        operation: String,
    ): Int {
        val value = requiredValue(root, path, operation)
        if (value !is JsonPrimitive || value.isString) {
            invalid(operation, "field '$path' must be an integer")
        }
        return value.content.toIntOrNull() ?: invalid(operation, "field '$path' must be an integer")
    }

    private fun requiredArray(
        root: JsonObject,
        path: String,
        operation: String,
    ): JsonArray {
        val value = requiredValue(root, path, operation)
        return value as? JsonArray ?: invalid(operation, "field '$path' must be an array")
    }

    private fun requiredObject(
        root: JsonObject,
        path: String,
        operation: String,
    ): JsonObject {
        val value = requiredValue(root, path, operation)
        return value as? JsonObject ?: invalid(operation, "field '$path' must be an object")
    }

    private fun requiredNullableString(
        root: JsonObject,
        path: String,
        operation: String,
    ): String? {
        val value = requiredValue(root, path, operation)
        if (value == JsonNull) return null
        return stringValue(value, path, operation)
    }

    private fun requiredNonBlankString(
        root: JsonObject,
        path: String,
        operation: String,
    ): String {
        val value = stringValue(requiredValue(root, path, operation), path, operation)
        if (value.isBlank()) invalid(operation, "field '$path' must not be blank")
        return value
    }

    private fun requiredBoolean(
        root: JsonObject,
        path: String,
        operation: String,
    ): Boolean {
        val value = requiredValue(root, path, operation)
        if (value !is JsonPrimitive || value.isString || value.content !in setOf("true", "false")) {
            invalid(operation, "field '$path' must be a boolean")
        }
        return value.content == "true"
    }

    private fun optionalString(
        root: JsonObject,
        path: String,
        operation: String,
    ): String? {
        val value = root[path]
        if (value == null || value == JsonNull) return null
        return stringValue(value, path, operation)
    }

    private fun stringValue(
        value: JsonElement,
        path: String,
        operation: String,
    ): String {
        if (value !is JsonPrimitive || !value.isString) {
            invalid(operation, "field '$path' must be a string or null")
        }
        return value.content
    }

    private fun requiredValue(
        root: JsonObject,
        path: String,
        operation: String,
    ): JsonElement {
        var current: JsonElement = root
        path.split('.').forEach { segment ->
            val currentObject = current as? JsonObject ?: invalid(operation, "field '$path' must be nested in an object")
            current = currentObject[segment] ?: invalid(operation, "missing required field '$path'")
        }
        return current
    }

    private fun invalid(
        operation: String,
        detail: String,
    ): Nothing =
        throw GatewayException(
            category = GatewayErrorCategory.INVALID_RESPONSE,
            message = "Invalid Gateway response for $operation: $detail.",
        )
}

internal data class RunAdmission(
    val runId: RunId,
    val status: String,
)

/**
 * One SSE record from the pinned Run observation stream.
 *
 * The pinned Gateway writes the event type inside the JSON `data:` payload
 * (`{"event": ..., "run_id": ..., ...}`) and never writes a named `event:` line
 * or an `id:` line; comment-only keepalives are ignored by [GatewaySseParser].
 */
internal data class GatewaySseFrame(
    val data: String,
)

internal object GatewaySseParser {
    fun frames(
        lines: Sequence<String>,
        operation: String,
    ): Sequence<GatewaySseFrame> =
        sequence {
            val record = mutableListOf<String>()
            for (line in lines) {
                if (line.isEmpty()) {
                    if (record.isNotEmpty()) {
                        parseRecord(record, operation)?.let { frame -> yield(frame) }
                        record.clear()
                    }
                } else {
                    record += line
                }
            }
            if (record.isNotEmpty()) {
                parseRecord(record, operation)?.let { frame -> yield(frame) }
            }
        }

    private fun parseRecord(
        lines: List<String>,
        operation: String,
    ): GatewaySseFrame? {
        val contentLines = lines.filterNot { it.startsWith(":") }
        if (contentLines.isEmpty()) return null
        val dataLines = contentLines.filter { it.startsWith("data:") }
        if (dataLines.isEmpty() || dataLines.size != contentLines.size) {
            throw invalidFraming(operation)
        }
        val data = dataLines.joinToString("\n") { it.removePrefix("data:").trimStart() }
        if (data.isBlank()) {
            throw invalidFraming(operation)
        }
        return GatewaySseFrame(data)
    }

    private fun invalidFraming(operation: String): GatewayException =
        GatewayException(
            GatewayErrorCategory.INVALID_RESPONSE,
            "Invalid Gateway response for $operation: invalid SSE event framing.",
        )
}
