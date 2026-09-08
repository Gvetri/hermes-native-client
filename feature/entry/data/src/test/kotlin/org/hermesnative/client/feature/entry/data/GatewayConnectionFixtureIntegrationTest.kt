package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

class GatewayConnectionFixtureIntegrationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )
    private val contractsRoot = repositoryRoot.resolve("fixtures/hermes/contracts")

    @Test
    fun endpoint_verification_accepts_unknown_additive_capabilities_and_saves_only_the_endpoint() {
        val repository = FakeGatewayConnectionRepository()
        val verification =
            VerifyGatewayConnection(repository) { endpoint, credential ->
                DefaultGatewayClient(
                    endpoint = endpoint,
                    bearerToken = credential,
                    transport = RecordingTransport(response("capabilities/unknown-additive.json")),
                ).discoverCapabilities()
            }

        val capabilities = verification.execute(" https://gateway.example/profile-a/ ", "fixture-token")

        assertTrue(capabilities.supports("gateway.future.capability"))
        assertEquals(GatewayConnection("https://gateway.example/profile-a/"), repository.saved)
    }

    @Test
    fun endpoint_verification_rejects_missing_required_capabilities_without_saving() {
        val repository = FakeGatewayConnectionRepository()
        val verification =
            VerifyGatewayConnection(repository) { endpoint, credential ->
                DefaultGatewayClient(
                    endpoint = endpoint,
                    bearerToken = credential,
                    transport = RecordingTransport(response("capabilities/missing-required.json")),
                ).discoverCapabilities()
            }

        val error = captureFailure { verification.execute("https://gateway.example/profile-a", "fixture-token") }

        assertEquals(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE, error.category)
        assertNull(repository.saved)
    }

    private fun response(path: String): GatewayHttpResponse {
        val root = Json.parseToJsonElement(contractsRoot.resolve(path).readText()).jsonObject
        val response = root.getValue("response").jsonObject
        return GatewayHttpResponse(
            statusCode = response.getValue("status").jsonPrimitive.content.toInt(),
            body = response.getValue("body").toString(),
        )
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

    private class RecordingTransport(
        response: GatewayHttpResponse,
    ) : GatewayTransport {
        private val responses = ArrayDeque(listOf(response))

        override fun execute(request: GatewayHttpRequest): GatewayHttpResponse = responses.removeFirst()

        override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream =
            error("The capability verification flow does not open an event stream")
    }
}
