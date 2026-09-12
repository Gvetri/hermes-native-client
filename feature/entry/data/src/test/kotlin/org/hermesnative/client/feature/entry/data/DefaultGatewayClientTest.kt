package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.feature.entry.domain.GatewayContractPort
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.cert.CertPathValidatorException
import java.util.ArrayDeque
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class DefaultGatewayClientTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )
    private val contractsRoot = repositoryRoot.resolve("fixtures/hermes/contracts")

    @Test
    fun adapter_executes_every_supported_operation_against_the_client_owned_fixtures() {
        val transport =
            RecordingTransport(
                responses =
                    listOf(
                        response("capabilities/success.json"),
                        response("sessions/list-response-page-1.json"),
                        response("sessions/create-response.json"),
                        response("sessions/open-response.json"),
                        response("sessions/history-response.json"),
                        response("sessions/rename-response.json"),
                        response("sessions/delete-response.json"),
                        response("sessions/pin-response.json"),
                        response("sessions/unpin-response.json"),
                        response("runs/create-response.json"),
                        response("runs/status-response.json"),
                    ),
                eventStream = GatewayEventStream(statusCode = 200, lines = sseLines("runs/observation.sse")),
            )
        val client = DefaultGatewayClient("https://gateway.example", "test-token", transport)
        val sessionId = SessionId(SESSION_ID)

        assertEquals(11, client.discoverCapabilities().identifiers.size)
        assertEquals(1, client.listSessions().sessions.size)
        assertEquals(CREATED_SESSION_ID, client.createSession(null).id.value)
        assertEquals(sessionId, client.openSession(sessionId).id)
        assertEquals(sessionId, client.loadSessionHistory(sessionId).sessionId)
        assertEquals("Renamed session", client.renameSession(sessionId, "Renamed session").title)
        client.deleteSession(sessionId)
        assertTrue(client.pinSession(sessionId).pinned)
        assertFalse(client.unpinSession(sessionId).pinned)
        assertEquals(RUN_ID, client.createRun(sessionId, "").id.value)
        assertEquals("succeeded", client.getRunStatus(RunId(RUN_ID)).status)
        assertEquals(
            listOf(RunEventType.STARTED, RunEventType.RUNNING, RunEventType.COMPLETED),
            client.observeRun(RunId(RUN_ID)).toList().map { it.type },
        )
        assertTrue(transport.eventStreamClosed)

        val expectedFixtures =
            listOf(
                "capabilities/request.json",
                "sessions/list-request.json",
                "sessions/create-request.json",
                "sessions/open-request.json",
                "sessions/history-request.json",
                "sessions/rename-request.json",
                "sessions/delete-request.json",
                "sessions/pin-request.json",
                "sessions/unpin-request.json",
                "runs/create-request.json",
                "runs/status-request.json",
            )
        assertEquals(expectedFixtures.size + 1, transport.requests.size)
        expectedFixtures.forEachIndexed { index, fixturePath ->
            assertFixtureRequest(transport.requests[index], fixturePath)
        }
        assertFixtureRequest(transport.requests.last(), "runs/status-request.json", expectedPath = "/v1/runs/$RUN_ID/events")
        assertEquals("text/event-stream", transport.requests.last().headers["Accept"])
    }

    @Test
    fun configured_profile_endpoint_is_used_without_discovery_or_route_extensions() {
        val transport = RecordingTransport(responses = listOf(response("capabilities/success.json")))
        val client = DefaultGatewayClient("https://gateway.example/profile-a/", "test-token", transport)

        client.discoverCapabilities()

        assertEquals("https://gateway.example/profile-a/v1/capabilities", transport.requests.single().url)
    }

    @Test
    fun session_list_forwards_and_encodes_server_search_and_cursor_parameters() {
        val transport = RecordingTransport(responses = listOf(response("sessions/list-response-page-1.json")))
        val client = DefaultGatewayClient("https://gateway.example", "test-token", transport)

        client.listSessions(
            SessionListRequest(
                limit = 7,
                cursor = "page two",
                search = "title & preview",
            ),
        )

        assertEquals(
            "limit=7&cursor=page%20two&search=title%20%26%20preview",
            URI(transport.requests.single().url).rawQuery,
        )
    }

    @Test
    fun capabilities_allow_unknown_additive_identifiers_but_reject_missing_required_identifiers() {
        val additiveClient =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "test-token",
                transport = RecordingTransport(responses = listOf(response("capabilities/unknown-additive.json"))),
            )

        val capabilities = additiveClient.discoverCapabilities()

        assertTrue(capabilities.supports("gateway.future.capability"))
        assertEquals(12, capabilities.identifiers.size)

        val missingClient =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "test-token",
                transport = RecordingTransport(responses = listOf(response("capabilities/missing-required.json"))),
            )

        assertFailure(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE) {
            missingClient.discoverCapabilities()
        }
    }

    @Test
    fun authentication_failure_is_safe_and_does_not_expose_the_bearer_token() {
        val client =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "secret-token",
                transport = RecordingTransport(responses = listOf(response("connection/authentication-failed.json"))),
            )

        val error = captureFailure { client.discoverCapabilities() }

        assertEquals(GatewayErrorCategory.AUTHENTICATION_FAILED, error.category)
        assertFalse(error.message.orEmpty().contains("secret-token"))
        assertFalse(error.message.orEmpty().contains("authentication_failed"))
    }

    @Test
    fun invalid_address_and_secure_connection_failures_have_stable_categories() {
        assertFailure(GatewayErrorCategory.INVALID_ADDRESS) {
            DefaultGatewayClient("http://gateway.example", "test-token", RecordingTransport())
        }

        listOf(
            SSLHandshakeException("certificate detail"),
            SSLPeerUnverifiedException("hostname mismatch"),
            CertPathValidatorException("unknown certificate authority"),
        ).forEach { failure ->
            val secureClient =
                DefaultGatewayClient(
                    endpoint = "https://gateway.example",
                    bearerToken = "test-token",
                    transport = RecordingTransport(failure = failure),
                )

            val error = captureFailure { secureClient.discoverCapabilities() }

            assertEquals(GatewayErrorCategory.SECURE_CONNECTION_FAILED, error.category)
            assertFalse(error.message.orEmpty().contains(failure.message.orEmpty()))
        }
    }

    @Test
    fun gateway_transport_failures_are_safe_and_stable() {
        val client =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "secret-token",
                transport = RecordingTransport(failure = IOException("Authorization: Bearer secret-token")),
            )

        val error = captureFailure { client.discoverCapabilities() }

        assertEquals(GatewayErrorCategory.GATEWAY_REQUEST_FAILED, error.category)
        assertFalse(error.message.orEmpty().contains("secret-token"))
        assertFalse(error.message.orEmpty().contains("Authorization"))
    }

    @Test
    fun malformed_json_fields_and_sse_framing_fail_with_invalid_response() {
        val invalidJsonClient = clientForResponse("malformed/invalid-json.json")
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) { invalidJsonClient.discoverCapabilities() }

        val missingFieldClient = clientForResponse("malformed/missing-required-field.json")
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) { missingFieldClient.listSessions() }

        val invalidTypeClient = clientForResponse("malformed/invalid-required-field-type.json")
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) { invalidTypeClient.listSessions() }

        val invalidSseClient =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "test-token",
                transport =
                    RecordingTransport(
                        eventStream =
                            GatewayEventStream(
                                statusCode = 200,
                                lines = sseLines("malformed/invalid-sse-framing.sse"),
                            ),
                    ),
            )

        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            invalidSseClient.observeRun(RunId(RUN_ID)).toList()
        }
    }

    @Test
    fun resource_identity_mismatches_are_rejected_as_invalid_responses() {
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-session-response.json").openSession(SessionId(SESSION_ID))
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-session-response.json").renameSession(SessionId(SESSION_ID), "Renamed")
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-history-response.json").loadSessionHistory(SessionId(SESSION_ID))
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-pin-response.json").pinSession(SessionId(SESSION_ID))
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-pin-response.json").unpinSession(SessionId(SESSION_ID))
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-run-create-response.json").createRun(SessionId(SESSION_ID), "input")
        }
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            clientForResponse("malformed/mismatched-run-status-response.json").getRunStatus(RunId(RUN_ID))
        }

        val mismatchedEventClient =
            DefaultGatewayClient(
                endpoint = "https://gateway.example",
                bearerToken = "test-token",
                transport =
                    RecordingTransport(
                        eventStream =
                            GatewayEventStream(
                                statusCode = 200,
                                lines = sseLines("malformed/mismatched-run-event.sse"),
                            ),
                    ),
            )
        assertFailure(GatewayErrorCategory.INVALID_RESPONSE) {
            mismatchedEventClient.observeRun(RunId(RUN_ID)).toList()
        }
    }

    @Test
    fun run_observation_opens_lazily_and_explicit_close_releases_partial_stream() {
        val transport =
            RecordingTransport(
                eventStream = GatewayEventStream(statusCode = 200, lines = sseLines("runs/observation.sse")),
            )
        val observation =
            DefaultGatewayClient("https://gateway.example", "test-token", transport)
                .observeRun(RunId(RUN_ID))

        assertTrue(transport.requests.isEmpty())
        val iterator = observation.iterator()
        assertTrue(transport.requests.isNotEmpty())
        assertTrue(iterator.hasNext())
        iterator.next()
        assertFalse(transport.eventStreamClosed)

        observation.close()

        assertTrue(transport.eventStreamClosed)
    }

    private fun clientForResponse(path: String): GatewayContractPort =
        DefaultGatewayClient(
            endpoint = "https://gateway.example",
            bearerToken = "test-token",
            transport = RecordingTransport(responses = listOf(response(path))),
        )

    private fun response(path: String): GatewayHttpResponse {
        val content = contractsRoot.resolve(path).readText()
        val root =
            try {
                Json.parseToJsonElement(content).jsonObject
            } catch (_: Exception) {
                return GatewayHttpResponse(statusCode = 200, body = content)
            }
        val response = root.getValue("response").jsonObject
        return GatewayHttpResponse(
            statusCode = response.getValue("status").jsonPrimitive.content.toInt(),
            body = response.getValue("body").toString(),
        )
    }

    private fun sseLines(path: String): Sequence<String> = contractsRoot.resolve(path).readLines().asSequence()

    private fun assertFixtureRequest(
        request: GatewayHttpRequest,
        fixturePath: String,
        expectedPath: String? = null,
    ) {
        val root = Json.parseToJsonElement(contractsRoot.resolve(fixturePath).readText()).jsonObject
        val expected = root.getValue("request").jsonObject
        assertEquals(expected.getValue("method").jsonPrimitive.content, request.method)
        assertEquals(expectedPath ?: expected.getValue("path").jsonPrimitive.content, URI(request.url).path)
        assertEquals("Bearer test-token", request.headers["Authorization"])

        val expectedQuery = expected["query"]?.jsonObject
        val rawQuery = URI(request.url).rawQuery
        if (expectedQuery == null) {
            assertNull(rawQuery)
        } else {
            assertEquals("limit=20", rawQuery)
            assertEquals("20", expectedQuery.getValue("limit").jsonPrimitive.content)
        }

        val expectedBody = expected["body"]?.jsonObject
        if (expectedBody == null) {
            assertNull(request.body)
            assertNull(request.headers["Content-Type"])
        } else {
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals(expectedBody, Json.parseToJsonElement(requireNotNull(request.body)).jsonObject)
        }
    }

    private fun captureFailure(block: () -> Unit): GatewayException {
        try {
            block()
        } catch (error: GatewayException) {
            return error
        }
        throw AssertionError("Expected GatewayException")
    }

    private fun assertFailure(
        category: GatewayErrorCategory,
        block: () -> Unit,
    ) {
        assertEquals(category, captureFailure(block).category)
    }

    private class RecordingTransport(
        responses: List<GatewayHttpResponse> = emptyList(),
        private val eventStream: GatewayEventStream? = null,
        private val failure: Exception? = null,
    ) : GatewayTransport {
        private val queuedResponses = ArrayDeque(responses)
        var eventStreamClosed = false
        val requests = mutableListOf<GatewayHttpRequest>()

        override fun execute(request: GatewayHttpRequest): GatewayHttpResponse {
            requests += request
            failure?.let { throw it }
            return queuedResponses.removeFirst()
        }

        override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream {
            requests += request
            failure?.let { throw it }
            val stream = requireNotNull(eventStream)
            return GatewayEventStream(stream.statusCode, stream.lines) {
                eventStreamClosed = true
                stream.close()
            }
        }
    }

    private companion object {
        const val SESSION_ID = "7c4d3b20-7c7a-4e2a-a593-3a11c2e93f70"
        const val CREATED_SESSION_ID = "b8f0b3e4-9d5b-4d31-8eb2-71c7fc5f4b26"
        const val RUN_ID = "9f54cb79-8b45-4c4d-a4e2-6e7b42d9b1c8"
    }
}
