package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The pinned `/v1/capabilities` endpoint names a journey can advertise. */
object JourneyEndpointCatalog {
    data class Endpoint(
        val method: String,
        val path: String,
    )

    val endpoints: Map<String, Endpoint> =
        linkedMapOf(
            "health" to Endpoint("GET", "/health"),
            "sessions" to Endpoint("GET", "/api/sessions"),
            "session_create" to Endpoint("POST", "/api/sessions"),
            "session" to Endpoint("GET", "/api/sessions/{session_id}"),
            "session_update" to Endpoint("PATCH", "/api/sessions/{session_id}"),
            "session_delete" to Endpoint("DELETE", "/api/sessions/{session_id}"),
            "session_messages" to Endpoint("GET", "/api/sessions/{session_id}/messages"),
            "runs" to Endpoint("POST", "/v1/runs"),
            "run_status" to Endpoint("GET", "/v1/runs/{run_id}"),
            "run_events" to Endpoint("GET", "/v1/runs/{run_id}/events"),
            "gateway_future_endpoint" to Endpoint("GET", "/v1/future"),
        )
}

/**
 * Explicit, repository-owned configuration for one deterministic emulator
 * journey. One scenario file backs exactly one Maestro flow.
 */
data class JourneyScenario(
    val name: String,
    val hermesRevision: String,
    val port: Int,
    val tls: Boolean,
    val capabilities: List<String>,
    val requireBearerCredential: String? = null,
    val sessionPageSize: Int? = null,
    val sessions: List<JourneySession> = emptyList(),
    val runs: List<JourneyRunScript> = emptyList(),
    val failNextSessionList: Boolean = false,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Journey scenario names must use lowercase kebab-case."
        }
        require(port in 1..65535) { "Journey scenario port must be valid." }
        require(capabilities.isNotEmpty()) { "Journey scenarios must declare capabilities." }
    }
}

data class JourneySession(
    val id: String,
    val title: String?,
    val preview: String?,
    val pinned: Boolean,
    val history: List<JourneyMessage> = emptyList(),
)

/** A pinned Session message projection has no run identity metadata. */
data class JourneyMessage(
    val id: String,
    val role: String?,
    val content: String?,
    val timestamp: String? = null,
)

data class JourneyRunScript(
    val runId: String,
    val sessionId: String,
    val createStatus: String = "started",
    val observation: List<JourneyRunEvent> = emptyList(),
    val interruptAfterEvents: Int? = null,
    val holdOpen: Boolean = false,
    val finalStatus: String? = null,
    val terminalHistory: List<JourneyMessage> = emptyList(),
) {
    init {
        require(interruptAfterEvents == null || interruptAfterEvents in 0..observation.size) {
            "interruptAfterEvents must be within the observation sequence."
        }
        require(!holdOpen || interruptAfterEvents == null) {
            "A held-open observation cannot interrupt."
        }
    }
}

/** One pinned Run SSE payload: `{"event": <type>, "run_id": ..., ...}`. */
data class JourneyRunEvent(
    val type: String,
    val delta: String? = null,
)

sealed class JourneyScenarioException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class JourneyScenarioFormatException(message: String, cause: Throwable? = null) :
    JourneyScenarioException(message, cause)

object JourneyScenarioParser {
    private val supportedEventTypes =
        setOf(
            "message.delta",
            "run.completed",
            "run.failed",
            "run.cancelled",
            "run.stopping",
            "tool.started",
            "tool.completed",
            "reasoning.available",
            "approval.request",
        )

    private val allowedTopLevelKeys =
        setOf(
            "name",
            "hermes_revision",
            "port",
            "tls",
            "capabilities",
            "require_bearer_credential",
            "session_page_size",
            "sessions",
            "runs",
            "fail_next_session_list",
        )
    private val allowedSessionKeys = setOf("id", "title", "preview", "pinned", "history")
    private val allowedMessageKeys = setOf("id", "role", "content", "timestamp")
    private val allowedRunKeys =
        setOf(
            "run_id",
            "session_id",
            "create_status",
            "observation",
            "interrupt_after_events",
            "hold_open",
            "final_status",
            "terminal_history",
        )
    private val allowedEventKeys = setOf("type", "delta")
    private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

    fun parse(
        scenarioFile: File,
        pinnedHermesRevision: String,
    ): JourneyScenario {
        val text = readScenarioText(scenarioFile)
        val normalizedText = normalizeUnicodeEscapes(text)
        requireSingleProvenanceField(normalizedText)
        val root = requireScenarioRoot(text)
        rejectUnknownKeys(root, allowedTopLevelKeys, "top-level")
        val scenario = requireScenarioFields(root)
        requireKnownEndpoints(scenario)
        requireProvenance(scenario, pinnedHermesRevision)
        return scenario
    }

    private fun readScenarioText(scenarioFile: File): String =
        runCatching { scenarioFile.readText() }.getOrElse { error ->
            throw JourneyScenarioFormatException("Journey scenario could not be read: ${error.message}")
        }

    private fun normalizeUnicodeEscapes(text: String): String =
        UNICODE_ESCAPE.replace(text) { match ->
            val codePoint = match.groupValues[1].toInt(16)
            if (codePoint in 0..0xD7FF || codePoint in 0xE000..0xFFFF) {
                codePoint.toChar().toString()
            } else {
                match.value
            }
        }

    private fun requireSingleProvenanceField(text: String) {
        val provenanceFieldCount = Regex("\"hermes_revision\"\\s*:").findAll(text).count()
        if (provenanceFieldCount != 1) {
            throw JourneyScenarioFormatException(
                "Journey scenario must declare exactly one hermes_revision provenance field.",
            )
        }
    }

    private fun requireScenarioRoot(text: String): JsonObject =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw JourneyScenarioFormatException("Journey scenario is not a JSON object.")

    private fun requireScenarioFields(root: JsonObject): JourneyScenario =
        try {
            JourneyScenario(
                name = requiredString(root, "name"),
                hermesRevision = requiredString(root, "hermes_revision"),
                port = requiredInt(root, "port"),
                tls = requiredBoolean(root, "tls"),
                capabilities = requiredStringList(root, "capabilities"),
                requireBearerCredential = optionalString(root, "require_bearer_credential"),
                sessionPageSize = optionalInt(root, "session_page_size"),
                sessions = parseSessions(root),
                runs = parseRuns(root),
                failNextSessionList = optionalBoolean(root, "fail_next_session_list", default = false),
            )
        } catch (error: IllegalArgumentException) {
            throw JourneyScenarioFormatException(error.message ?: "Journey scenario is invalid.", error)
        }

    private fun requireKnownEndpoints(scenario: JourneyScenario) {
        val unknown = scenario.capabilities.filterNot(JourneyEndpointCatalog.endpoints::containsKey)
        if (unknown.isNotEmpty()) {
            throw JourneyScenarioFormatException(
                "Journey scenario declares unsupported capability endpoints: ${unknown.sorted()}",
            )
        }
    }

    private fun requireProvenance(
        scenario: JourneyScenario,
        pinnedHermesRevision: String,
    ) {
        if (scenario.hermesRevision != pinnedHermesRevision) {
            throw JourneyScenarioFormatException(
                "Journey scenario provenance does not match the pinned fixture descriptor.",
            )
        }
    }

    private fun parseSessions(root: JsonObject): List<JourneySession> =
        root["sessions"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val session = element.jsonObject
            rejectUnknownKeys(session, allowedSessionKeys, "session[$index]")
            JourneySession(
                id = requiredString(session, "id"),
                title = optionalString(session, "title"),
                preview = optionalString(session, "preview"),
                pinned = optionalBoolean(session, "pinned", default = false),
                history = parseMessages(session, "history", "session[$index].history"),
            )
        }

    private fun parseMessages(
        container: JsonObject,
        key: String,
        context: String,
    ): List<JourneyMessage> =
        container[key]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val message = element.jsonObject
            rejectUnknownKeys(message, allowedMessageKeys, "$context[$index]")
            JourneyMessage(
                id = requiredString(message, "id"),
                role = optionalString(message, "role"),
                content = optionalString(message, "content"),
                timestamp = optionalString(message, "timestamp"),
            )
        }

    private fun parseRuns(root: JsonObject): List<JourneyRunScript> =
        root["runs"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val run = element.jsonObject
            rejectUnknownKeys(run, allowedRunKeys, "run[$index]")
            JourneyRunScript(
                runId = requiredString(run, "run_id"),
                sessionId = requiredString(run, "session_id"),
                createStatus = optionalString(run, "create_status") ?: "started",
                observation = parseObservation(run, index),
                interruptAfterEvents = optionalInt(run, "interrupt_after_events"),
                holdOpen = optionalBoolean(run, "hold_open", default = false),
                finalStatus = optionalString(run, "final_status"),
                terminalHistory = parseMessages(run, "terminal_history", "run[$index].terminal_history"),
            )
        }

    private fun parseObservation(
        run: JsonObject,
        runIndex: Int,
    ): List<JourneyRunEvent> =
        run["observation"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val event = element.jsonObject
            rejectUnknownKeys(event, allowedEventKeys, "run[$runIndex].observation[$index]")
            val type = requiredString(event, "type")
            if (type !in supportedEventTypes) {
                throw JourneyScenarioFormatException(
                    "Journey scenario declares unsupported SSE event type '$type'.",
                )
            }
            JourneyRunEvent(
                type = type,
                delta = optionalString(event, "delta"),
            )
        }

    private fun rejectUnknownKeys(
        container: JsonObject,
        allowed: Set<String>,
        context: String,
    ) {
        val unknown = container.keys - allowed
        if (unknown.isNotEmpty()) {
            throw JourneyScenarioFormatException(
                "Journey scenario $context declares unsupported fields: ${unknown.sorted()}",
            )
        }
    }

    private fun requiredString(
        container: JsonObject,
        key: String,
    ): String {
        val value = container[key] as? JsonPrimitive ?: throw JourneyScenarioFormatException("Missing field '$key'.")
        return value.content.takeIf(String::isNotBlank)
            ?: throw JourneyScenarioFormatException("Field '$key' must not be blank.")
    }

    private fun optionalString(
        container: JsonObject,
        key: String,
    ): String? {
        val value = container[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.content
            ?: throw JourneyScenarioFormatException("Field '$key' must be a string or null.")
    }

    private fun requiredInt(
        container: JsonObject,
        key: String,
    ): Int =
        (container[key] as? JsonPrimitive)?.intOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be an integer.")

    private fun optionalInt(
        container: JsonObject,
        key: String,
    ): Int? {
        val value = container[key] ?: return null
        if (value == JsonNull) return null
        return (value as? JsonPrimitive)?.intOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be an integer or null.")
    }

    private fun requiredBoolean(
        container: JsonObject,
        key: String,
    ): Boolean =
        (container[key] as? JsonPrimitive)?.booleanOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be a boolean.")

    private fun optionalBoolean(
        container: JsonObject,
        key: String,
        default: Boolean,
    ): Boolean {
        val value = container[key] ?: return default
        return (value as? JsonPrimitive)?.booleanOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be a boolean.")
    }

    private fun requiredStringList(
        container: JsonObject,
        key: String,
    ): List<String> =
        container[key]?.jsonArray.orEmpty().map { element ->
            (element as? JsonPrimitive)?.content
                ?: throw JourneyScenarioFormatException("Field '$key' must be a list of strings.")
        }
}
