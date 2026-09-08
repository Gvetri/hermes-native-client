package org.hermesnative.client.buildlogic

import java.io.File

/** The validated, repository-owned provenance and lifecycle contract for the deterministic fixture. */
data class PinnedHermesFixtureDescriptor(
    val schemaVersion: Int,
    val name: String,
    val provenanceField: String,
    val provenanceValue: String,
    val fields: Map<String, String>,
)

/** Validates the intentionally small properties format used by the deterministic fixture descriptor. */
object FixtureDescriptorValidator {
    private val keyPattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
    private val gitRevisionPattern = Regex("[0-9a-fA-F]{40}")
    private val imageDigestPattern = Regex("sha256:[0-9a-f]{64}")

    private val requiredFields =
        setOf(
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

    private val allowedFields = requiredFields + setOf("hermes_revision", "image_digest")

    /** Reads and validates a repository descriptor file. */
    fun validate(file: File): PinnedHermesFixtureDescriptor {
        require(file.isFile) { "Fixture descriptor does not exist: ${file.path}" }
        return validate(file.readText())
    }

    /** Validates a descriptor and returns its typed provenance and lifecycle fields. */
    fun validate(content: String): PinnedHermesFixtureDescriptor {
        val fields = parseFields(content)
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
            "Descriptor contains unsupported or environment-specific field '$unsupportedField'. " +
                "Do not include credentials, provider credentials, endpoint secrets, private host details, " +
                "or environment-specific configuration."
        }

        val blankField = fields.keys.firstOrNull { fields.getValue(it).isBlank() }
        require(blankField == null) {
            "Descriptor field '$blankField' must not be empty."
        }

        val schemaVersion =
            fields.getValue("schema_version").toIntOrNull()
                ?: throw IllegalArgumentException("Descriptor schema_version must be 1.")
        require(schemaVersion == 1) {
            "Descriptor schema_version must be 1."
        }

        val name = fields.getValue("name")
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Descriptor name must use lowercase kebab-case."
        }

        val provenanceField = provenanceFields.single()
        val provenanceValue = fields.getValue(provenanceField)
        when (provenanceField) {
            "hermes_revision" -> {
                require(gitRevisionPattern.matches(provenanceValue)) {
                    "hermes_revision must be a full 40-character immutable Git revision; " +
                        "mutable refs such as main and latest are not allowed."
                }
            }

            "image_digest" -> {
                require(imageDigestPattern.matches(provenanceValue)) {
                    "image_digest must be an immutable sha256 digest; mutable refs such as main and latest " +
                        "are not allowed."
                }
            }
        }

        require(fields.getValue("startup.command") == "build-and-run-pinned-hermes") {
            "startup.command must be build-and-run-pinned-hermes."
        }
        require(fields.getValue("startup.mode") == "ephemeral") {
            "startup.mode must be ephemeral."
        }
        require(fields.getValue("startup.network") == "isolated") {
            "startup.network must be isolated."
        }
        require(fields.getValue("startup.supervisor") == "disabled") {
            "startup.supervisor must be disabled."
        }
        require(fields.getValue("health_check.method") == "GET") {
            "health_check.method must be GET."
        }
        require(fields.getValue("health_check.path") == "/health") {
            "health_check.path must be /health."
        }
        require(fields.getValue("health_check.expected_status") == "200") {
            "health_check.expected_status must be 200."
        }
        require(fields.getValue("capability_check.method") == "GET") {
            "capability_check.method must be GET."
        }
        require(fields.getValue("capability_check.path") == "/v1/capabilities") {
            "capability_check.path must be /v1/capabilities."
        }
        require(fields.getValue("capability_check.expected_status") == "200") {
            "capability_check.expected_status must be 200."
        }
        require(fields.getValue("capability_check.required_capabilities") == "client-manifest") {
            "capability_check.required_capabilities must be client-manifest."
        }
        require(fields.getValue("synthetic_test_data.setup") == "client-owned-synthetic-state") {
            "synthetic_test_data.setup must be client-owned-synthetic-state."
        }
        requireTrueField(fields, "synthetic_test_data.reset_before_test")
        requireTrueField(fields, "synthetic_test_data.reset_after_test")
        require(fields.getValue("teardown.on_success") == "required") {
            "teardown.on_success must be required."
        }
        require(fields.getValue("teardown.on_failure") == "required") {
            "teardown.on_failure must be required."
        }
        requireTrueField(fields, "teardown.verify_process_exit")
        requireTrueField(fields, "teardown.verify_state_reset")

        return PinnedHermesFixtureDescriptor(
            schemaVersion = schemaVersion,
            name = name,
            provenanceField = provenanceField,
            provenanceValue = provenanceValue,
            fields = fields,
        )
    }

    private fun parseFields(content: String): LinkedHashMap<String, String> {
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
        return fields
    }

    private fun requireTrueField(
        fields: Map<String, String>,
        field: String,
    ) {
        require(fields.getValue(field) == "true") {
            "$field must be true."
        }
    }
}
