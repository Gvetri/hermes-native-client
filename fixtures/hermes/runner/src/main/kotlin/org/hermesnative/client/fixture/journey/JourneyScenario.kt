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

data class JourneyMessage(
    val id: String,
    val role: String?,
    val content: String?,
    val runId: String? = null,
    val runStatus: String? = null,
    val runResult: String? = null,
    val timestamp: String? = null,
)

data class JourneyRunScript(
    val runId: String,
    val sessionId: String,
    val createStatus: String = "starting",
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

data class JourneyRunEvent(
    val type: String,
    val status: String? = null,
    val delta: String? = null,
    val id: String? = null,
)

sealed class JourneyScenarioException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class JourneyScenarioFormatException(message: String) : JourneyScenarioException(message)

object JourneyScenarioParser {
    private val supportedEventTypes =
        setOf(
            "run.started",
            "run.running",
            "run.completing",
            "message.delta",
            "run.succeeded",
            "run.failed",
            "run.interrupted",
            "run.completed",
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
    private val allowedMessageKeys = setOf("id", "role", "content", "run_id", "run_status", "run_result", "timestamp")
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
    private val allowedEventKeys = setOf("type", "status", "delta", "id")

    fun parse(
        scenarioFile: File,
        pinnedHermesRevision: String,
    ): JourneyScenario {
        val text =
            runCatching { scenarioFile.readText() }.getOrElse { error ->
                throw JourneyScenarioFormatException("Journey scenario could not be read: ${error.message}")
            }
        val root =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw JourneyScenarioFormatException("Journey scenario is not a JSON object.")
        rejectUnknownKeys(root, allowedTopLevelKeys, "top-level")
        val scenario =
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
                throw JourneyScenarioFormatException(error.message ?: "Journey scenario is invalid.")
            }
        requireProvenance(scenario, pinnedHermesRevision)
        return scenario
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
                history = parseMessages(session, "session[$index].history"),
            )
        }

    private fun parseMessages(
        container: JsonObject,
        context: String,
    ): List<JourneyMessage> =
        container["history"]?.jsonArray.orEmpty().mapIndexed { index, element ->
            val message = element.jsonObject
            rejectUnknownKeys(message, allowedMessageKeys, "$context[$index]")
            JourneyMessage(
                id = requiredString(message, "id"),
                role = optionalString(message, "role"),
                content = optionalString(message, "content"),
                runId = optionalString(message, "run_id"),
                runStatus = optionalString(message, "run_status"),
                runResult = optionalString(message, "run_result"),
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
                createStatus = optionalString(run, "create_status") ?: "starting",
                observation = parseObservation(run, index),
                interruptAfterEvents = optionalInt(run, "interrupt_after_events"),
                holdOpen = optionalBoolean(run, "hold_open", default = false),
                finalStatus = optionalString(run, "final_status"),
                terminalHistory = parseMessages(run, "run[$index].terminal_history"),
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
                status = optionalString(event, "status"),
                delta = optionalString(event, "delta"),
                id = optionalString(event, "id"),
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
