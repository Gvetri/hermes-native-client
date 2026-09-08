package org.hermesnative.client.fixture

import java.io.File

internal data class PinnedFixtureProvenance(
    val field: String,
    val value: String,
)

/** The validated repository-owned descriptor used to start a deterministic fixture. */
class PinnedFixtureDescriptor internal constructor(
    val fields: Map<String, String>,
    val provenanceField: String,
    val provenanceValue: String,
) {
    val name: String
        get() = fields.getValue("name")

    internal val provenance: PinnedFixtureProvenance
        get() = PinnedFixtureProvenance(provenanceField, provenanceValue)

    fun value(key: String): String = fields.getValue(key)

    companion object {
        private val keyPattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
        private val gitRevisionPattern = Regex("[0-9a-fA-F]{40}")
        private val imageDigestPattern = Regex("sha256:[0-9a-f]{64}")
        private val requiredFields =
            listOf(
                "schema_version",
                "name",
                "startup.command",
                "startup.mode",
                "startup.network",
                "startup.supervisor",
                "health_check.method",
                "health_check.path",
                "health_check.expected_status",
                "capability_check.method",
                "capability_check.path",
                "capability_check.expected_status",
                "capability_check.required_capabilities",
                "synthetic_test_data.setup",
                "synthetic_test_data.reset_before_test",
                "synthetic_test_data.reset_after_test",
                "teardown.on_success",
                "teardown.on_failure",
                "teardown.verify_process_exit",
                "teardown.verify_state_reset",
            )
        private val allowedFields = requiredFields.toSet() + setOf("hermes_revision", "image_digest")

        fun load(file: File): PinnedFixtureDescriptor {
            require(file.isFile) { "Fixture descriptor does not exist: ${file.path}" }
            return parse(file.readText())
        }

        private fun parse(content: String): PinnedFixtureDescriptor {
            val fields = linkedMapOf<String, String>()
            content.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    return@forEachIndexed
                }

                val separator = line.indexOf('=')
                require(separator > 0) {
                    "Descriptor line ${index + 1} must use key=value format."
                }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(keyPattern.matches(key)) {
                    "Descriptor line ${index + 1} has an invalid field name '$key'."
                }
                require(!fields.containsKey(key)) {
                    "Duplicate descriptor field '$key'."
                }
                fields[key] = value
            }

            require(fields.isNotEmpty()) { "Descriptor must contain fields." }
            val provenanceFields = fields.keys.filter { it == "hermes_revision" || it == "image_digest" }
            require(provenanceFields.size == 1) {
                "Descriptor must define exactly one immutable provenance field: hermes_revision or image_digest."
            }
            val missingFields = requiredFields.filterNot(fields::containsKey)
            require(missingFields.isEmpty()) {
                "Descriptor is missing required lifecycle fields: ${missingFields.joinToString(", ")}."
            }
            val unsupportedField = fields.keys.firstOrNull { it !in allowedFields }
            require(unsupportedField == null) {
                "Descriptor contains unsupported or environment-specific field '$unsupportedField'."
            }
            val blankField = fields.keys.firstOrNull { fields.getValue(it).isBlank() }
            require(blankField == null) { "Descriptor field '$blankField' must not be empty." }

            require(fields.getValue("schema_version") == "1") {
                "Descriptor schema_version must be 1."
            }
            require(fields.getValue("name").matches(Regex("[a-z][a-z0-9-]*"))) {
                "Descriptor name must use lowercase kebab-case."
            }

            val provenanceField = provenanceFields.single()
            val provenanceValue = fields.getValue(provenanceField)
            when (provenanceField) {
                "hermes_revision" ->
                    require(gitRevisionPattern.matches(provenanceValue)) {
                        "hermes_revision must be a full 40-character immutable Git revision."
                    }

                "image_digest" ->
                    require(imageDigestPattern.matches(provenanceValue)) {
                        "image_digest must be an immutable sha256 digest."
                    }
            }

            requireValue(fields, "startup.command", "build-and-run-pinned-hermes")
            requireValue(fields, "startup.mode", "ephemeral")
            requireValue(fields, "startup.network", "isolated")
            requireValue(fields, "startup.supervisor", "disabled")
            requireValue(fields, "health_check.method", "GET")
            requireValue(fields, "health_check.path", "/health")
            requireValue(fields, "health_check.expected_status", "200")
            requireValue(fields, "capability_check.method", "GET")
            requireValue(fields, "capability_check.path", "/v1/capabilities")
            requireValue(fields, "capability_check.expected_status", "200")
            requireValue(fields, "capability_check.required_capabilities", "client-manifest")
            requireValue(fields, "synthetic_test_data.setup", "client-owned-synthetic-state")
            requireValue(fields, "teardown.on_success", "required")
            requireValue(fields, "teardown.on_failure", "required")
            requireTrue(fields, "synthetic_test_data.reset_before_test")
            requireTrue(fields, "synthetic_test_data.reset_after_test")
            requireTrue(fields, "teardown.verify_process_exit")
            requireTrue(fields, "teardown.verify_state_reset")

            return PinnedFixtureDescriptor(fields, provenanceField, provenanceValue)
        }

        private fun requireValue(
            fields: Map<String, String>,
            key: String,
            expected: String,
        ) {
            require(fields.getValue(key) == expected) {
                "$key must be $expected."
            }
        }

        private fun requireTrue(
            fields: Map<String, String>,
            key: String,
        ) {
            require(fields.getValue(key) == "true") { "$key must be true." }
        }
    }
}
