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

private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val HEX_RADIX = 16
private const val SURROGATE_RANGE_END = 0xD7FF
private const val SURROGATE_RANGE_START = 0xE000
private const val CODE_POINT_MAX = 0xFFFF
private const val MAX_HOLD_CREATE_MILLIS = 9000

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
            "run_models" to Endpoint("GET", "/api/models"),
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
    val failAfterFirstSessionList: Boolean = false,
    val offlineRecoversAfterBlockedSessionLists: Int? = null,
    val failAfterFirstSessionOpen: Boolean = false,
    val offlineRecoversAfterBlockedSessionOpens: Int? = null,
    val holdCreateMs: Int? = null,
    val modelCatalog: JourneyModelCatalog? = null,
    val modelCatalogRow: JourneyModelCatalogRow? = null,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Journey scenario names must use lowercase kebab-case."
        }
        require(port in MIN_PORT..MAX_PORT) { "Journey scenario port must be valid." }
        require(capabilities.isNotEmpty()) { "Journey scenarios must declare capabilities." }
        require(holdCreateMs == null || holdCreateMs in 1..MAX_HOLD_CREATE_MILLIS) {
            "hold_create_ms must be between 1 and 9000 milliseconds."
        }
        require(modelCatalogRow == null || "run_models" in capabilities) {
            "A model catalog row override requires the run_models capability."
        }
        require(
            offlineRecoversAfterBlockedSessionLists == null ||
                (failAfterFirstSessionList && offlineRecoversAfterBlockedSessionLists >= 1),
        ) {
            "Offline recovery counts require the after-first-list offline gate and a positive bound."
        }
        require(
            offlineRecoversAfterBlockedSessionOpens == null ||
                (failAfterFirstSessionOpen && offlineRecoversAfterBlockedSessionOpens >= 1),
        ) {
            "Offline recovery counts for Session opens require the after-first-open offline gate and a positive bound."
        }
    }
}

/**
 * The additive model catalog served at `GET /api/models` for journeys that script the
 * advertised catalog flow. The shape follows the pinned `contracts/models` fixture: a
 * nullable `default_model` and non-empty entries; an entry without efforts is selectable
 * with no effort control.
 */
data class JourneyModelCatalog(
    val defaultModel: String?,
    val entries: List<JourneyModelCatalogEntry>,
) {
    init {
        require(entries.isNotEmpty()) { "A journey model catalog must contain at least one model entry." }
    }
}

data class JourneyModelCatalogEntry(
    val id: String,
    val displayName: String,
    val provider: String?,
    val supportedEfforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A journey model catalog entry must carry a non-blank id." }
        require(displayName.isNotBlank()) { "A journey model catalog entry must carry a non-blank display name." }
        require(supportedEfforts.isNotEmpty() || defaultEffort == null) {
            "A model entry without efforts cannot declare a default effort."
        }
        require(supportedEfforts.none(String::isBlank)) { "Journey model effort vocabulary values must not be blank." }
        require(defaultEffort == null || defaultEffort in supportedEfforts) {
            "A model entry default effort must be one of its supported efforts."
        }
    }
}

/** A deliberately mismatched `run_models` capability row: exact-method/path gates decide detection. */
data class JourneyModelCatalogRow(
    val method: String,
    val path: String,
) {
    init {
        require(method.isNotBlank()) { "A journey model catalog row override must carry a non-blank method." }
        require(path.isNotBlank()) { "A journey model catalog row override must carry a non-blank path." }
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
    val finalizeOnDisconnect: Boolean = false,
    val refuseCreate: Boolean = false,
    val admissionRefusalCode: String? = null,
    val liveFraming: Boolean = false,
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
        require(!finalizeOnDisconnect || (holdOpen && finalStatus != null)) {
            "A disconnect-finalized observation must hold open and declare its final status."
        }
        require(
            !refuseCreate ||
                (observation.isEmpty() && finalStatus == null && terminalHistory.isEmpty()),
        ) {
            "A refused Run creation cannot script observation or terminal behavior."
        }
        require(admissionRefusalCode == null || !refuseCreate) {
            "A Run cannot both fail creation and refuse admission."
        }
        require(
            admissionRefusalCode == null ||
                (
                    admissionRefusalCode.isNotBlank() &&
                        observation.isEmpty() && finalStatus == null && terminalHistory.isEmpty()
                ),
        ) {
            "An admission-refused Run cannot carry a blank code or script observation or terminal behavior."
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
        "fail_after_first_session_list",
        "offline_recovers_after_blocked_session_lists",
        "fail_after_first_session_open",
        "offline_recovers_after_blocked_session_opens",
        "hold_create_ms",
        "model_catalog",
        "model_catalog_row",
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
        "finalize_on_disconnect",
        "refuse_create",
        "admission_refusal_code",
        "live_framing",
        "final_status",
        "terminal_history",
    )
private val allowedEventKeys = setOf("type", "delta")
private val allowedCatalogKeys = setOf("default_model", "models")
private val allowedCatalogEntryKeys = setOf("id", "display_name", "provider", "effort")
private val allowedEffortKeys = setOf("supported", "default")
private val allowedCatalogRowKeys = setOf("method", "path")
private val UNICODE_ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")

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

object JourneyScenarioParser {
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
            val codePoint = match.groupValues[1].toInt(HEX_RADIX)
            if (codePoint in 0..SURROGATE_RANGE_END || codePoint in SURROGATE_RANGE_START..CODE_POINT_MAX) {
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
                name = JourneyScenarioJson.requiredString(root, "name"),
                hermesRevision = JourneyScenarioJson.requiredString(root, "hermes_revision"),
                port = JourneyScenarioJson.requiredInt(root, "port"),
                tls = JourneyScenarioJson.requiredBoolean(root, "tls"),
                capabilities = JourneyScenarioJson.requiredStringList(root, "capabilities"),
                requireBearerCredential = JourneyScenarioJson.optionalString(root, "require_bearer_credential"),
                sessionPageSize = JourneyScenarioJson.optionalInt(root, "session_page_size"),
                sessions = parseSessions(root),
                runs = JourneyScenarioJson.parseRuns(root),
                failNextSessionList =
                    JourneyScenarioJson.optionalBoolean(root, "fail_next_session_list", default = false),
                failAfterFirstSessionList =
                    JourneyScenarioJson.optionalBoolean(root, "fail_after_first_session_list", default = false),
                offlineRecoversAfterBlockedSessionLists =
                    JourneyScenarioJson.optionalInt(root, "offline_recovers_after_blocked_session_lists"),
                failAfterFirstSessionOpen =
                    JourneyScenarioJson.optionalBoolean(root, "fail_after_first_session_open", default = false),
                offlineRecoversAfterBlockedSessionOpens =
                    JourneyScenarioJson.optionalInt(root, "offline_recovers_after_blocked_session_opens"),
                holdCreateMs = JourneyScenarioJson.optionalInt(root, "hold_create_ms"),
                modelCatalog = JourneyScenarioCatalog.parseModelCatalog(root),
                modelCatalogRow = JourneyScenarioCatalog.parseModelCatalogRow(root),
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
                id = JourneyScenarioJson.requiredString(session, "id"),
                title = JourneyScenarioJson.optionalString(session, "title"),
                preview = JourneyScenarioJson.optionalString(session, "preview"),
                pinned = JourneyScenarioJson.optionalBoolean(session, "pinned", default = false),
                history = JourneyScenarioJson.parseMessages(session, "history", "session[$index].history"),
            )
        }
}

private object JourneyScenarioCatalog {
    internal fun parseModelCatalog(root: JsonObject): JourneyModelCatalog? {
        val catalog = root["model_catalog"] ?: return null
        val catalogObject =
            catalog as? JsonObject
                ?: throw JourneyScenarioFormatException("The model catalog must be a JSON object.")
        rejectUnknownKeys(catalogObject, allowedCatalogKeys, "model_catalog")
        val models =
            catalogObject["models"]?.jsonArray
                ?: throw JourneyScenarioFormatException("The model catalog must declare a models list.")
        return JourneyModelCatalog(
            defaultModel = nullableRequiredString(catalogObject, "default_model"),
            entries =
                models.mapIndexed { index, element ->
                    parseModelCatalogEntry(element.jsonObject, index)
                },
        )
    }

    private fun parseModelCatalogEntry(
        entry: JsonObject,
        index: Int,
    ): JourneyModelCatalogEntry {
        rejectUnknownKeys(entry, allowedCatalogEntryKeys, "model_catalog.models[$index]")
        val effort = entry["effort"]?.jsonObject
        effort?.let { rejectUnknownKeys(it, allowedEffortKeys, "model_catalog.models[$index].effort") }
        if (effort != null && effort["supported"] == null) {
            throw JourneyScenarioFormatException("model_catalog.models[$index].effort must declare supported efforts.")
        }
        return JourneyModelCatalogEntry(
            id = JourneyScenarioJson.requiredString(entry, "id"),
            displayName = JourneyScenarioJson.requiredString(entry, "display_name"),
            provider = JourneyScenarioJson.optionalString(entry, "provider"),
            supportedEfforts = effort?.let { JourneyScenarioJson.requiredStringList(it, "supported") }.orEmpty(),
            defaultEffort = effort?.let { JourneyScenarioJson.optionalString(it, "default") },
        )
    }

    internal fun parseModelCatalogRow(root: JsonObject): JourneyModelCatalogRow? {
        val row = root["model_catalog_row"] ?: return null
        val rowObject =
            row as? JsonObject
                ?: throw JourneyScenarioFormatException("The model catalog row override must be a JSON object.")
        rejectUnknownKeys(rowObject, allowedCatalogRowKeys, "model_catalog_row")
        return JourneyModelCatalogRow(
            method = JourneyScenarioJson.requiredString(rowObject, "method"),
            path = JourneyScenarioJson.requiredString(rowObject, "path"),
        )
    }

    internal fun nullableRequiredString(
        container: JsonObject,
        key: String,
    ): String? {
        val value =
            container[key]
                ?: throw JourneyScenarioFormatException("Field '$key' is required and may be null.")
        return if (value == JsonNull) {
            null
        } else {
            (value as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                ?: throw JourneyScenarioFormatException("Field '$key' must be a non-blank string or null.")
        }
    }
}

private object JourneyScenarioJson {
    internal fun parseMessages(
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

    internal fun parseRuns(root: JsonObject): List<JourneyRunScript> =
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
                finalizeOnDisconnect = optionalBoolean(run, "finalize_on_disconnect", default = false),
                refuseCreate = optionalBoolean(run, "refuse_create", default = false),
                admissionRefusalCode = optionalString(run, "admission_refusal_code"),
                liveFraming = optionalBoolean(run, "live_framing", default = false),
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

    internal fun requiredString(
        container: JsonObject,
        key: String,
    ): String {
        val value = container[key] as? JsonPrimitive ?: throw JourneyScenarioFormatException("Missing field '$key'.")
        return value.content.takeIf(String::isNotBlank)
            ?: throw JourneyScenarioFormatException("Field '$key' must not be blank.")
    }

    internal fun optionalString(
        container: JsonObject,
        key: String,
    ): String? {
        val value = container[key] ?: return null
        return if (value == JsonNull) {
            null
        } else {
            (value as? JsonPrimitive)?.content
                ?: throw JourneyScenarioFormatException("Field '$key' must be a string or null.")
        }
    }

    internal fun requiredInt(
        container: JsonObject,
        key: String,
    ): Int =
        (container[key] as? JsonPrimitive)?.intOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be an integer.")

    internal fun optionalInt(
        container: JsonObject,
        key: String,
    ): Int? {
        val value = container[key] ?: return null
        return if (value == JsonNull) {
            null
        } else {
            (value as? JsonPrimitive)?.intOrNull
                ?: throw JourneyScenarioFormatException("Field '$key' must be an integer or null.")
        }
    }

    internal fun requiredBoolean(
        container: JsonObject,
        key: String,
    ): Boolean =
        (container[key] as? JsonPrimitive)?.booleanOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be a boolean.")

    internal fun optionalBoolean(
        container: JsonObject,
        key: String,
        default: Boolean,
    ): Boolean {
        val value = container[key] ?: return default
        return (value as? JsonPrimitive)?.booleanOrNull
            ?: throw JourneyScenarioFormatException("Field '$key' must be a boolean.")
    }

    internal fun requiredStringList(
        container: JsonObject,
        key: String,
    ): List<String> =
        container[key]?.jsonArray.orEmpty().map { element ->
            (element as? JsonPrimitive)?.content
                ?: throw JourneyScenarioFormatException("Field '$key' must be a list of strings.")
        }
}
