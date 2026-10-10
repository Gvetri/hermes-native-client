package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

class JourneyVerifierTest {
    @Test
    fun verifier_accepts_declared_journeys_and_fails_for_undeclared_ones() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            get(endpoint, "/health")
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            streamEvents(endpoint, "run-success")
            get(endpoint, "/v1/runs/run-success")
            val telemetry = fetchTelemetry(endpoint.resolve("/__fixture/telemetry"))
            JourneyInvariants.verify("terminal-success", telemetry)
            val unknown =
                runCatching { JourneyInvariants.verify("not-a-journey", telemetry) }.exceptionOrNull()
            assertTrue(unknown is IllegalStateException)
        } finally {
            process.stop()
        }
    }

    @Test
    fun telemetry_distinguishes_confirmed_session_operations_without_retaining_titles() {
        val process = startGateway("session-list-first", tls = false)
        try {
            val client = HttpClient.newHttpClient()
            listOf(
                """{"title":"do-not-export-title"}""",
                """{"pinned":true}""",
                """{"pinned":false}""",
            ).forEach { body ->
                val request =
                    HttpRequest.newBuilder(process.endpoint.resolve("/api/sessions/session-alpha"))
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
                assertEquals(200, client.send(request, BodyHandlers.ofString()).statusCode())
            }
            val telemetry = fetchTelemetry(process.endpoint.resolve("/__fixture/telemetry"))
            assertFalse(telemetry.toString().contains("do-not-export-title"))
            assertEquals(
                listOf("rename", "pin", "unpin"),
                telemetry["session_mutations"]?.jsonArray.orEmpty()
                    .map { it.jsonObject.getValue("operation").jsonPrimitive.content },
            )
        } finally {
            process.stop()
        }
    }

    @Test
    fun lifecycle_verifier_rejects_extra_mutations_from_unconfirmed_actions() {
        val telemetry = lifecycleTelemetry(lifecycleRequests + ("DELETE" to "/api/sessions/session-alpha"))
        assertThrows(IllegalStateException::class.java) {
            JourneyInvariants.verify("session-lifecycle", telemetry)
        }
    }

    @Test
    fun lifecycle_verifier_accepts_only_the_complete_targeted_management_sequence() {
        JourneyInvariants.verify("session-lifecycle", lifecycleTelemetry(lifecycleRequests))
        assertThrows(IllegalStateException::class.java) {
            JourneyInvariants.verify("session-lifecycle", lifecycleTelemetry(lifecycleRequests.dropLast(1)))
        }
        val wrongTarget =
            lifecycleRequests.map {
                    (method, path) ->
                method to path.replace("synthetic-created-session-1", "session-alpha")
            }
        assertThrows(IllegalStateException::class.java) {
            JourneyInvariants.verify("session-lifecycle", lifecycleTelemetry(wrongTarget))
        }
    }

    @Test
    fun lifecycle_verifier_rejects_a_wrong_operation_with_the_same_http_method_and_path() {
        val wrongOperations = lifecycleOperations.toMutableList()
        wrongOperations[2] = "unpin" to "synthetic-created-session-1"
        assertThrows(IllegalStateException::class.java) {
            JourneyInvariants.verify("session-lifecycle", lifecycleTelemetry(operations = wrongOperations))
        }
    }

    @Test
    fun lifecycle_verifier_rejects_a_mutation_before_the_conversation_cancellation_checkpoint() {
        assertEarlyMutationRejected("synthetic-created-session-1")
    }

    @Test
    fun lifecycle_verifier_rejects_a_mutation_before_the_list_cancellation_checkpoint() {
        assertEarlyMutationRejected("session-alpha")
    }

    @Test
    fun verifier_trusts_the_committed_ca_when_fetching_https_telemetry() {
        val keystoreFile = File(repositoryRoot, "fixtures/hermes/journey-tls/journey-gateway.p12")
        val scenarioFile = File(scenariosDir, "terminal-success.json")
        val scenario =
            JourneyScenarioParser.parse(scenarioFile, pinnedDescriptor.provenance.value)
                .copy(port = freePort(), tls = true)
        val process = JourneyGatewayProcess.start(scenario, keystoreFile)
        try {
            val telemetry = fetchTelemetry(process.endpoint.resolve("/__fixture/telemetry"), keystoreFile)
            assertTrue(telemetry.toString().contains("terminal-success"))
        } finally {
            process.stop()
        }
    }

    private fun assertEarlyMutationRejected(sessionId: String) {
        val requests = lifecycleRequests.toMutableList()
        val firstMutation = requests.indexOfFirst { it == "PATCH" to "/api/sessions/$sessionId" }
        assertEquals("GET", requests[firstMutation - 1].first)
        val mutation = requests.removeAt(firstMutation)
        requests.add(firstMutation - 1, mutation)
        assertThrows(IllegalStateException::class.java) {
            JourneyInvariants.verify("session-lifecycle", lifecycleTelemetry(requests))
        }
    }

    private val lifecycleRequests =
        listOf(
            "GET" to "/api/sessions",
            "POST" to "/api/sessions",
            "GET" to "/api/sessions/synthetic-created-session-1",
            "GET" to "/api/sessions/synthetic-created-session-1/messages",
            "GET" to "/api/sessions",
            "GET" to "/api/sessions/synthetic-created-session-1",
            "GET" to "/api/sessions/synthetic-created-session-1/messages",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "GET" to "/api/sessions",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "GET" to "/api/sessions",
            "DELETE" to "/api/sessions/synthetic-created-session-1",
            "GET" to "/api/sessions",
            "PATCH" to "/api/sessions/session-alpha",
            "PATCH" to "/api/sessions/session-alpha",
            "GET" to "/api/sessions",
            "PATCH" to "/api/sessions/session-alpha",
            "GET" to "/api/sessions",
            "DELETE" to "/api/sessions/session-alpha",
        )

    private val lifecycleOperations =
        listOf(
            "create" to "synthetic-created-session-1",
            "rename" to "synthetic-created-session-1",
            "pin" to "synthetic-created-session-1",
            "unpin" to "synthetic-created-session-1",
            "delete" to "synthetic-created-session-1",
            "rename" to "session-alpha",
            "pin" to "session-alpha",
            "unpin" to "session-alpha",
            "delete" to "session-alpha",
        )

    private fun lifecycleTelemetry(
        requests: List<Pair<String, String>> = lifecycleRequests,
        operations: List<Pair<String, String>> = lifecycleOperations,
    ) = buildJsonObject {
        put(
            "session_mutations",
            buildJsonArray {
                operations.forEach { (operation, sessionId) ->
                    add(
                        buildJsonObject {
                            put("operation", operation)
                            put("session_id", sessionId)
                        },
                    )
                }
            },
        )
        put(
            "requests",
            buildJsonArray {
                (listOf("GET" to "/v1/capabilities") + requests).forEach { (method, path) ->
                    add(
                        buildJsonObject {
                            put("method", method)
                            put("path", path)
                        },
                    )
                }
            },
        )
    }
}
