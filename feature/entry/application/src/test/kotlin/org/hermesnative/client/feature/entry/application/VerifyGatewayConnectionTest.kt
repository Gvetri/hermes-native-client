package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifyGatewayConnectionTest {
    @Test
    fun saves_only_the_trimmed_endpoint_after_all_required_capabilities_are_present() {
        val repository = FakeGatewayConnectionRepository()
        var receivedEndpoint: String? = null
        var receivedCredential: String? = null
        val verifier =
            VerifyGatewayConnection(repository) { endpoint, credential ->
                receivedEndpoint = endpoint
                receivedCredential = credential
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers + "future.capability")
            }

        val capabilities = verifier.execute("  https://gateway.example/profile  ", "memory-only-token")

        assertEquals("https://gateway.example/profile", receivedEndpoint)
        assertEquals("memory-only-token", receivedCredential)
        assertEquals(12, capabilities.identifiers.size)
        assertEquals(GatewayConnection("https://gateway.example/profile"), repository.saved)
    }

    @Test
    fun rejects_missing_required_capabilities_without_persisting_the_endpoint() {
        val repository = FakeGatewayConnectionRepository()
        val verifier =
            VerifyGatewayConnection(repository) { _, _ ->
                GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers.toList().dropLast(1).toSet())
            }

        val error = captureFailure { verifier.execute("https://gateway.example", "token") }

        assertEquals(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE, error.category)
        assertNull(repository.saved)
    }

    @Test
    fun rejects_blank_endpoint_and_credential_before_verification() {
        val repository = FakeGatewayConnectionRepository()
        val verifier = VerifyGatewayConnection(repository) { _, _ -> error("must not verify") }

        assertEquals(
            GatewayErrorCategory.INVALID_ADDRESS,
            captureFailure { verifier.execute("  ", "token") }.category,
        )
        assertEquals(
            GatewayErrorCategory.AUTHENTICATION_FAILED,
            captureFailure { verifier.execute("https://gateway.example", "  ") }.category,
        )
    }

    @Test
    fun rejects_insecure_or_malformed_endpoints_before_verification() {
        val repository = FakeGatewayConnectionRepository()
        var verificationCalls = 0
        val verifier =
            VerifyGatewayConnection(repository) { _, _ ->
                verificationCalls += 1
                error("must not verify")
            }

        listOf(
            "http://gateway.example",
            "https://",
            "https://gateway.example:0",
            "https://gateway.example:65536",
            "https://user@gateway.example",
            "https://gateway.example?profile=one",
            "https://gateway.example#profile",
            "https://gateway.example/%",
        ).forEach { endpoint ->
            assertEquals(
                "endpoint=$endpoint",
                GatewayErrorCategory.INVALID_ADDRESS,
                captureFailure { verifier.execute(endpoint, "token") }.category,
            )
        }

        assertNull(repository.saved)
        assertEquals(0, verificationCalls)
    }

    private fun captureFailure(block: () -> Unit): GatewayException {
        try {
            block()
        } catch (error: GatewayException) {
            return error
        }
        throw AssertionError("Expected GatewayException")
    }

    private class FakeGatewayConnectionRepository : GatewayConnectionRepository {
        var saved: GatewayConnection? = null

        override fun load(): GatewayConnection? = saved

        override fun save(connection: GatewayConnection) {
            saved = connection
        }
    }
}
