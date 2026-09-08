package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.hermesnative.client.feature.entry.domain.SessionPinResult

internal object GatewayJsonParser {
    private val json = Json

    fun parseObject(
        operation: String,
        content: String,
    ): JsonObject {
        val element =
            try {
                json.parseToJsonElement(content)
            } catch (_: Exception) {
                throw invalid(operation, "the response is not valid JSON")
            }
        return element as? JsonObject ?: invalid(operation, "the response must be a JSON object")
    }

    fun parseCapabilities(
        operation: String,
        root: JsonObject,
    ): Set<String> = requiredStringArray(root, "capabilities", operation).toSet()

    fun parseSessionPage(
        operation: String,
        root: JsonObject,
    ): SessionPage =
        SessionPage(
            sessions =
                requiredArray(root, "sessions", operation).mapIndexed { index, value ->
                    parseSession(value, "$operation.sessions[$index]")
                },
            nextCursor = requiredNullableString(root, "next_cursor", operation),
        )

    fun parseSession(
        operation: String,
        root: JsonObject,
    ): Session {
        val session = requiredObject(root, "session", operation)
        return parseSession(session, "$operation.session")
    }

    fun parseHistory(
        operation: String,
        root: JsonObject,
    ): SessionHistory {
        val messages =
            requiredArray(root, "messages", operation).mapIndexed { index, value ->
                parseMessage(value, "$operation.messages[$index]")
            }
        return SessionHistory(
            sessionId = SessionId(requiredNonBlankString(root, "session_id", operation)),
            messages = messages,
            nextCursor = requiredNullableString(root, "next_cursor", operation),
        )
    }

    fun parsePinResult(
        operation: String,
        root: JsonObject,
    ): SessionPinResult =
        SessionPinResult(
            sessionId = SessionId(requiredNonBlankString(root, "session_id", operation)),
            pinned = requiredBoolean(root, "pinned", operation),
        )

    fun parseRun(
        operation: String,
        root: JsonObject,
    ): Run =
        Run(
            id = RunId(requiredNonBlankString(root, "run_id", operation)),
            sessionId = SessionId(requiredNonBlankString(root, "session_id", operation)),
            status = requiredNonBlankString(root, "status", operation),
        )

    fun parseRunEvent(
        operation: String,
        frame: GatewaySseFrame,
    ): RunEvent? {
        val eventType =
            when (frame.eventType) {
                "run.started" -> RunEventType.STARTED
                "run.running" -> RunEventType.RUNNING
                "run.completed" -> RunEventType.COMPLETED
                else -> return null
            }
        val root = parseObject("$operation.${frame.eventType}", frame.data)
        return RunEvent(
            type = eventType,
            runId = RunId(requiredNonBlankString(root, "run_id", operation)),
            status = requiredNonBlankString(root, "status", operation),
        )
    }

    private fun parseSession(
        value: JsonElement,
        operation: String,
    ): Session {
        val session = value as? JsonObject ?: invalid(operation, "session must be a JSON object")
        return Session(
            id = SessionId(requiredNonBlankString(session, "id", operation)),
            title = requiredNullableString(session, "title", operation),
            preview = requiredNullableString(session, "preview", operation),
            pinned = requiredBoolean(session, "pinned", operation),
            updatedAt = requiredNullableString(session, "updated_at", operation),
        )
    }

    private fun parseMessage(
        value: JsonElement,
        operation: String,
    ): GatewayHistoryMessage {
        val message = value as? JsonObject ?: invalid(operation, "message must be a JSON object")
        return GatewayHistoryMessage(
            id = optionalString(message, "id", operation),
            role = optionalString(message, "role", operation),
            content = optionalString(message, "content", operation),
        )
    }

    private fun requiredStringArray(
        root: JsonObject,
        path: String,
        operation: String,
    ): List<String> =
        requiredArray(root, path, operation).mapIndexed { index, value ->
            if (value !is JsonPrimitive || !value.isString) {
                invalid(operation, "field '$path[$index]' must be a string")
            }
            value.content
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
        val value = root[path] ?: return null
        if (value == JsonNull) return null
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

internal data class GatewaySseFrame(
    val eventType: String,
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
                        yield(parseRecord(record, operation))
                        record.clear()
                    }
                } else {
                    record += line
                }
            }
            if (record.isNotEmpty()) {
                yield(parseRecord(record, operation))
            }
        }

    private fun parseRecord(
        lines: List<String>,
        operation: String,
    ): GatewaySseFrame {
        val eventLines = lines.filter { it.startsWith("event:") }
        val dataLines = lines.filter { it.startsWith("data:") }
        if (eventLines.size != 1 || dataLines.isEmpty() || lines.size != eventLines.size + dataLines.size) {
            throw GatewayException(
                GatewayErrorCategory.INVALID_RESPONSE,
                "Invalid Gateway response for $operation: invalid SSE event framing.",
            )
        }
        val eventType = eventLines.single().removePrefix("event:").trim()
        if (eventType.isEmpty()) {
            throw GatewayException(
                GatewayErrorCategory.INVALID_RESPONSE,
                "Invalid Gateway response for $operation: SSE event type is missing.",
            )
        }
        return GatewaySseFrame(
            eventType = eventType,
            data = dataLines.joinToString("\n") { it.removePrefix("data:").trimStart() },
        )
    }
}
