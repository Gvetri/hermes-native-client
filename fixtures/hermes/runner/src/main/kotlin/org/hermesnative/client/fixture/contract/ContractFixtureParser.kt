package org.hermesnative.client.fixture.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hermesnative.client.fixture.PinnedFixtureDescriptor

class ContractJsonFixture(
    val sourceName: String,
    val provenanceField: String,
    val provenanceValue: String,
    val root: JsonObject,
) {
    fun requiredString(path: String): String = fields().requiredString(path)

    fun requiredNullableString(path: String): String? = fields().requiredNullableString(path)

    fun requiredInt(path: String): Int = fields().requiredInt(path)

    fun requiredBoolean(path: String): Boolean = fields().requiredBoolean(path)

    fun requiredArray(path: String): JsonArray = fields().requiredArray(path)

    fun requiredObject(path: String): JsonObject = fields().requiredObject(path)

    fun requiredStringArray(path: String): List<String> = fields().requiredStringArray(path)

    private fun fields(): RequiredJsonFields = RequiredJsonFields(sourceName, root)
}

data class ContractSseEvent(
    val eventType: String,
    val data: JsonObject,
) {
    fun requiredString(path: String): String = fields().requiredString(path)

    fun requiredInt(path: String): Int = fields().requiredInt(path)

    private fun fields(): RequiredJsonFields = RequiredJsonFields("SSE event '$eventType'", data)
}

class ContractSseFixture(
    val sourceName: String,
    val provenanceField: String,
    val provenanceValue: String,
    val events: List<ContractSseEvent>,
)

object ContractFixtureParser {
    private val json = Json
    private val provenanceFields = setOf("hermes_revision", "image_digest")

    fun parseJson(
        sourceName: String,
        content: String,
        descriptor: PinnedFixtureDescriptor,
    ): ContractJsonFixture {
        val root =
            try {
                json.parseToJsonElement(content) as? JsonObject
                    ?: fail(
                        ContractFixtureFailureCategory.INVALID_JSON,
                        "$sourceName must contain a JSON object.",
                    )
            } catch (error: ContractFixtureException) {
                throw error
            } catch (error: Exception) {
                throw ContractFixtureException(
                    ContractFixtureFailureCategory.INVALID_JSON,
                    "$sourceName is not valid JSON.",
                    error,
                )
            }
        val provenance = validateProvenance(sourceName, content, root, descriptor)
        return ContractJsonFixture(
            sourceName = sourceName,
            provenanceField = descriptor.provenanceField,
            provenanceValue = provenance,
            root = root,
        )
    }

    fun parseSse(
        sourceName: String,
        content: String,
        descriptor: PinnedFixtureDescriptor,
    ): ContractSseFixture {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.endsWith("\n")) {
            fail(
                ContractFixtureFailureCategory.INVALID_SSE_FRAMING,
                "$sourceName must end with a newline after the final event.",
            )
        }
        val eventContent = if (normalized.endsWith("\n\n")) normalized.dropLast(2) else normalized.dropLast(1)
        val records = eventContent.split("\n\n")
        if (records.isEmpty() || records.any(String::isBlank)) {
            fail(
                ContractFixtureFailureCategory.INVALID_SSE_FRAMING,
                "$sourceName contains an empty SSE event record.",
            )
        }

        val events = records.map { record -> parseEvent(sourceName, record) }
        val provenanceData =
            events.mapNotNull { event ->
                event.data.takeIf { it.containsKey(descriptor.provenanceField) }
            }.singleOrNull()
        val provenance =
            validateProvenance(
                sourceName = sourceName,
                content = content,
                root = provenanceData,
                descriptor = descriptor,
            )
        return ContractSseFixture(
            sourceName = sourceName,
            provenanceField = descriptor.provenanceField,
            provenanceValue = provenance,
            events = events,
        )
    }

    private fun parseEvent(
        sourceName: String,
        record: String,
    ): ContractSseEvent {
        val lines = record.split('\n')
        val eventLines = lines.filter { it.startsWith("event:") }
        val dataLines = lines.filter { it.startsWith("data:") }
        if (eventLines.size != 1 || dataLines.isEmpty() || lines.size != eventLines.size + dataLines.size) {
            fail(
                ContractFixtureFailureCategory.INVALID_SSE_FRAMING,
                "$sourceName contains an SSE record with invalid event/data framing.",
            )
        }
        val eventType = eventLines.single().removePrefix("event:").trim()
        if (eventType.isEmpty()) {
            fail(
                ContractFixtureFailureCategory.INVALID_SSE_FRAMING,
                "$sourceName contains an SSE event without an event type.",
            )
        }
        val dataContent = dataLines.joinToString("\n") { it.removePrefix("data:").trimStart() }
        val data =
            try {
                json.parseToJsonElement(dataContent) as? JsonObject
                    ?: fail(
                        ContractFixtureFailureCategory.INVALID_REQUIRED_FIELD_TYPE,
                        "$sourceName event '$eventType' must contain a JSON object.",
                    )
            } catch (error: ContractFixtureException) {
                throw error
            } catch (error: Exception) {
                throw ContractFixtureException(
                    ContractFixtureFailureCategory.INVALID_JSON,
                    "$sourceName event '$eventType' is not valid JSON.",
                    error,
                )
            }
        return ContractSseEvent(eventType, data)
    }

    private fun validateProvenance(
        sourceName: String,
        content: String,
        root: JsonObject?,
        descriptor: PinnedFixtureDescriptor,
    ): String {
        val selectedCount = countField(content, descriptor.provenanceField)
        val alternateField = provenanceFields.first { it != descriptor.provenanceField }
        val alternateCount = countField(content, alternateField)
        if (selectedCount == 0 && alternateCount == 0) {
            fail(
                ContractFixtureFailureCategory.MISSING_PROVENANCE,
                "$sourceName must contain ${descriptor.provenanceField} provenance.",
            )
        }
        if (selectedCount == 0 && alternateCount > 0) {
            fail(
                ContractFixtureFailureCategory.INVALID_PROVENANCE,
                "$sourceName must use the pinned ${descriptor.provenanceField} provenance field.",
            )
        }
        if (selectedCount != 1 || alternateCount != 0) {
            fail(
                ContractFixtureFailureCategory.DUPLICATE_PROVENANCE,
                "$sourceName must contain exactly one ${descriptor.provenanceField} provenance field.",
            )
        }
        val value = root?.get(descriptor.provenanceField)
        if (value !is JsonPrimitive || !value.isString) {
            fail(
                ContractFixtureFailureCategory.INVALID_REQUIRED_FIELD_TYPE,
                "$sourceName provenance must be a JSON string.",
            )
        }
        if (value.content != descriptor.provenanceValue) {
            fail(
                ContractFixtureFailureCategory.INVALID_PROVENANCE,
                "$sourceName provenance does not match the pinned descriptor.",
            )
        }
        return value.content
    }

    private fun countField(
        content: String,
        field: String,
    ): Int = Regex("\\\"${Regex.escape(field)}\\\"\\s*:").findAll(content).count()

    private fun fail(
        category: ContractFixtureFailureCategory,
        message: String,
    ): Nothing = throw ContractFixtureException(category, message)
}

private class RequiredJsonFields(
    private val sourceName: String,
    private val root: JsonObject,
) {
    fun requiredString(path: String): String {
        val value = required(path)
        if (value !is JsonPrimitive || !value.isString) {
            invalidType(path, "string")
        }
        return value.content
    }

    fun requiredNullableString(path: String): String? {
        val value = required(path)
        if (value == JsonNull) return null
        return requiredString(path)
    }

    fun requiredInt(path: String): Int {
        val value = required(path)
        if (value !is JsonPrimitive || value.isString) invalidType(path, "integer")
        return value.content.toIntOrNull() ?: invalidType(path, "integer")
    }

    fun requiredBoolean(path: String): Boolean {
        val value = required(path)
        if (value !is JsonPrimitive || value.isString || value.content !in setOf("true", "false")) {
            invalidType(path, "boolean")
        }
        return value.content == "true"
    }

    fun requiredArray(path: String): JsonArray {
        val value = required(path)
        if (value !is JsonArray) invalidType(path, "array")
        return value
    }

    fun requiredObject(path: String): JsonObject {
        val value = required(path)
        if (value !is JsonObject) invalidType(path, "object")
        return value
    }

    fun requiredStringArray(path: String): List<String> =
        requiredArray(path).mapIndexed { index, value ->
            if (value !is JsonPrimitive || !value.isString) {
                invalidType("$path[$index]", "string")
            }
            value.content
        }

    private fun required(path: String): JsonElement {
        var current: JsonElement = root
        path.split('.').forEach { segment ->
            val jsonObject = current as? JsonObject
            if (jsonObject == null) invalidType(path, "object containing '$segment'")
            current = jsonObject[segment] ?: missing(path)
        }
        return current
    }

    private fun missing(path: String): Nothing =
        throw ContractFixtureException(
            ContractFixtureFailureCategory.MISSING_REQUIRED_FIELD,
            "$sourceName is missing required field '$path'.",
        )

    private fun invalidType(
        path: String,
        expected: String,
    ): Nothing =
        throw ContractFixtureException(
            ContractFixtureFailureCategory.INVALID_REQUIRED_FIELD_TYPE,
            "$sourceName field '$path' must be an $expected.",
        )
}
