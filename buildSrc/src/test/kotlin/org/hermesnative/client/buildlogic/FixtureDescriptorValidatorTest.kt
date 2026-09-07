package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FixtureDescriptorValidatorTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    @Test
    fun accepts_an_immutable_hermes_revision() {
        val descriptor = FixtureDescriptorValidator.validate(validDescriptor("hermes_revision=$hermesRevision"))

        assertEquals("hermes_revision", descriptor.provenanceField)
        assertEquals(hermesRevision, descriptor.provenanceValue)
    }

    @Test
    fun accepts_an_immutable_image_digest() {
        val descriptor = FixtureDescriptorValidator.validate(validDescriptor("image_digest=$imageDigest"))

        assertEquals("image_digest", descriptor.provenanceField)
        assertEquals(imageDigest, descriptor.provenanceValue)
    }

    @Test
    fun rejects_a_descriptor_without_provenance() {
        assertValidationFails(
            validDescriptor(),
            "exactly one immutable provenance field",
        )
    }

    @Test
    fun rejects_a_descriptor_with_both_provenance_fields() {
        assertValidationFails(
            validDescriptor("hermes_revision=$hermesRevision", "image_digest=$imageDigest"),
            "exactly one immutable provenance field",
        )
    }

    @Test
    fun rejects_a_mutable_or_malformed_provenance_value() {
        assertValidationFails(
            validDescriptor("hermes_revision=main"),
            "hermes_revision must be a full 40-character immutable Git revision",
        )
        assertValidationFails(
            validDescriptor("image_digest=latest"),
            "image_digest must be an immutable sha256 digest",
        )
    }

    @Test
    fun rejects_a_descriptor_with_missing_lifecycle_fields() {
        assertValidationFails(
            validDescriptor("hermes_revision=$hermesRevision")
                .replace("teardown.verify_state_reset=true\n", ""),
            "teardown.verify_state_reset",
        )
    }

    @Test
    fun rejects_sensitive_or_environment_specific_fields() {
        assertValidationFails(
            validDescriptor("hermes_revision=$hermesRevision", "gateway_endpoint=https://example.invalid"),
            "environment-specific",
        )
    }

    @Test
    fun rejects_duplicate_fields() {
        assertValidationFails(
            validDescriptor("hermes_revision=$hermesRevision", "schema_version=1"),
            "Duplicate descriptor field 'schema_version'",
        )
    }

    @Test
    fun validates_the_checked_in_descriptor() {
        val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")
        val descriptor = FixtureDescriptorValidator.validate(descriptorFile)

        assertEquals("hermes_revision", descriptor.provenanceField)
        assertEquals(hermesRevision, descriptor.provenanceValue)
    }

    private fun assertValidationFails(
        content: String,
        expectedMessage: String,
    ) {
        try {
            FixtureDescriptorValidator.validate(content)
            fail("Expected descriptor validation to fail with '$expectedMessage'.")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Expected '${error.message}' to contain '$expectedMessage'.",
                error.message.orEmpty().contains(expectedMessage),
            )
        }
    }

    private fun validDescriptor(vararg overrides: String): String =
        listOf(
            "schema_version=1",
            "name=hermes-deterministic-gateway",
            "startup.command=build-and-run-pinned-hermes",
            "startup.mode=ephemeral",
            "startup.network=isolated",
            "startup.supervisor=disabled",
            "health_check.method=GET",
            "health_check.path=/health",
            "health_check.expected_status=200",
            "capability_check.method=GET",
            "capability_check.path=/v1/capabilities",
            "capability_check.required_capabilities=client-manifest",
            "synthetic_test_data.setup=client-owned-synthetic-state",
            "synthetic_test_data.reset_before_test=true",
            "synthetic_test_data.reset_after_test=true",
            "teardown.on_success=required",
            "teardown.on_failure=required",
            "teardown.verify_process_exit=true",
            "teardown.verify_state_reset=true",
            *overrides,
        ).joinToString("\n", postfix = "\n")

    private companion object {
        const val hermesRevision = "d9833c5615b80e199a174cd67d90ab430695a972"
        const val imageDigest =
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
