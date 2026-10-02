package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.fixture.PinnedFixtureDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class JourneyScenarioTest {
    private val repositoryRoot =
        File(requireNotNull(System.getProperty("fixture.repositoryRoot")) { "fixture.repositoryRoot must be set." })
    private val scenariosDir = File(repositoryRoot, "fixtures/hermes/journey/scenarios")
    private val pinnedDescriptor =
        PinnedFixtureDescriptor.load(File(repositoryRoot, "fixtures/hermes/pinned-fixture.properties"))

    @Test
    fun every_checked_in_scenario_parses_with_the_pinned_revision() {
        val scenarioFiles = scenariosDir.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue("No journey scenarios found.", scenarioFiles.isNotEmpty())
        scenarioFiles.forEach { file ->
            val scenario = JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value)
            assertEquals(file.nameWithoutExtension, scenario.name)
        }
    }

    @Test
    fun every_journey_serves_valid_capability_json_before_device_execution() {
        scenariosDir.listFiles { file -> file.extension == "json" }.orEmpty().forEach { file ->
            val scenario = JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value)
            val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
            try {
                val response = get(process.endpoint, "/v1/capabilities", scenario.requireBearerCredential)
                assertTrue(response.startsWith("status=200 body="))
                val body = Json.parseToJsonElement(response.substringAfter(" body=")).jsonObject
                assertEquals("hermes.api_server.capabilities", body.getValue("object").jsonPrimitive.content)
                assertEquals("synthetic", body.getValue("model").jsonPrimitive.content)
                val endpoints = body.getValue("endpoints").jsonObject
                assertEquals(scenario.capabilities.toSet(), endpoints.keys)
                endpoints.forEach { (name, value) ->
                    val expected = JourneyEndpointCatalog.endpoints.getValue(name)
                    assertEquals(expected.method, value.jsonObject.getValue("method").jsonPrimitive.content)
                    assertEquals(expected.path, value.jsonObject.getValue("path").jsonPrimitive.content)
                }
            } finally {
                process.stop()
            }
        }
    }

    @Test
    fun session_list_serves_the_pinned_list_object_with_endpoint_data() {
        val process = startGateway("session-list-first", tls = false)
        try {
            val page = Json.parseToJsonElement(getBody(process.endpoint, "/api/sessions")).jsonObject
            assertEquals("list", page.getValue("object").jsonPrimitive.content)
            val sessions = page.getValue("data").jsonArray
            assertEquals(2, sessions.size)
            assertEquals("session-alpha", sessions[0].jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(true, sessions[0].jsonObject.getValue("pinned").jsonPrimitive.content.toBoolean())
            assertEquals(50, page.getValue("limit").jsonPrimitive.int)
            assertEquals(0, page.getValue("offset").jsonPrimitive.int)
        } finally {
            process.stop()
        }
    }

    @Test
    fun empty_session_list_returns_a_successful_empty_page() {
        val process = startGateway("empty-sessions", tls = false)
        try {
            assertEquals(
                "status=200 body={\"object\":\"list\",\"data\":[],\"limit\":50,\"offset\":0,\"has_more\":false}",
                get(process.endpoint, "/api/sessions"),
            )
        } finally {
            process.stop()
        }
    }

    @Test
    fun connection_recovery_flow_uses_the_visible_verification_action() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/connection.yaml").readText()
        val correctedCredential = "synthetic-token"
        assertTrue(flow.contains(correctedCredential))
        val recoverySteps = flow.substringAfter(correctedCredential)
        assertTrue(recoverySteps.contains("Verify Gateway Connection"))
        assertFalse(recoverySteps.contains("Try again"))
    }

    @Test
    fun retry_journey_uses_targeted_scroll_and_retries_unregistered_taps() {
        val flow = File(repositoryRoot, "fixtures/hermes/journey/flows/explicit-retry.yaml").readText()
        assertTrue(flow.contains("text: \"Try again\"\n    retryTapIfNoChange: true"))
        assertTrue(flow.contains("scrollUntilVisible:"))
        assertFalse(flow.contains("- swipe:"))
    }

    @Test
    fun provenance_mismatch_is_rejected() {
        val mismatched = File(repositoryRoot, "fixtures/hermes/journey/scenarios/connection.json")
        val error =
            runCatching {
                JourneyScenarioParser.parse(mismatched, "0000000000000000000000000000000000000000")
            }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("provenance"))
    }

    @Test
    fun unknown_top_level_field_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"unknown-field","hermes_revision":"$revision",""" +
                    """"port":18443,"tls":false,"capabilities":["sessions"],"mutable":"latest"}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("unsupported"))
    }

    @Test
    fun unknown_capability_endpoint_is_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"unknown-endpoint","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","not_an_endpoint"]}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("capability"))
    }

    @Test
    fun interrupted_and_held_open_run_scripts_are_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """
                {"name":"invalid-run","hermes_revision":"$revision","port":18443,"tls":false,"capabilities":["sessions"],
                 "sessions":[{"id":"s1","title":"S","preview":null,"pinned":false}],
                 "runs":[{"run_id":"r1","session_id":"s1","hold_open":true,"interrupt_after_events":1,"observation":[{"type":"tool.started"}]}]}
                """.trimIndent(),
            )
        val error = runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun terminal_success_gateway_serves_health_capabilities_sessions_runs_and_telemetry() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            assertEquals(200, get(endpoint, "/health"))
            val capabilities = get(endpoint, "/v1/capabilities")
            assertTrue(capabilities.contains("\"sessions\""))
            assertTrue(capabilities.contains("\"run_events\""))

            val sessions = get(endpoint, "/api/sessions")
            assertTrue(sessions.contains("Alpha Session"))

            val create = post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            assertEquals(202, create.status)
            assertTrue(create.body.contains("run-success"))
            assertTrue(create.body.contains("started"))

            val events = streamEvents(endpoint, "run-success")
            assertEquals(listOf("tool.started", "run.completed"), events.map { it.type })

            assertEquals(200, get(endpoint, "/v1/runs/run-success"))
            val statusBody = get(endpoint, "/v1/runs/run-success")
            assertTrue(statusBody.contains("completed"))

            val telemetry = Json.parseToJsonElement(getBody(endpoint, "/__fixture/telemetry")).toString()
            assertTrue(telemetry.contains("run-success"))
            assertTrue(telemetry.contains("run_status_requests"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun terminal_history_is_served_after_the_stream_completes() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            val events = streamEvents(endpoint, "run-success")
            assertEquals(2, events.size)
            val history = getBody(endpoint, "/api/sessions/session-alpha/messages")
            assertTrue(history.contains("message-success"))
            assertTrue(history.contains("Stable result"))
            assertFalse(history.contains("run_id"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun run_submissions_beyond_the_scripted_runs_are_refused_and_counted() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            streamEvents(endpoint, "run-success")
            val resubmit = post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            assertEquals(404, resubmit.status)
            assertFalse(resubmit.body.contains("run-success"))
            val telemetry = Json.parseToJsonElement(getBody(endpoint, "/__fixture/telemetry")).jsonObject
            assertEquals(
                2,
                telemetry.getValue("run_creates").jsonObject.getValue("session-alpha").jsonPrimitive.int,
            )
        } finally {
            process.stop()
        }
    }

    @Test
    fun duplicate_provenance_keys_are_rejected() {
        val file = File.createTempFile("journey-scenario", ".json")
        try {
            val pinned = pinnedDescriptor.provenance.value
            file.writeText(
                """{"name":"duplicate-provenance","hermes_revision":"$pinned","hermes_revision":"$pinned"}""",
            )
            val error =
                assertThrows(JourneyScenarioFormatException::class.java) {
                    JourneyScenarioParser.parse(file, pinned)
                }
            assertTrue(error.message.orEmpty().contains("exactly one hermes_revision"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun duplicate_provenance_keys_with_unicode_escapes_are_rejected() {
        val file = File.createTempFile("journey-scenario", ".json")
        try {
            val pinned = pinnedDescriptor.provenance.value
            val escapedRevision = "hermes_revision".replaceFirst("h", "\\u0068")
            file.writeText(
                """{"name":"duplicate-provenance","hermes_revision":"$pinned","$escapedRevision":"$pinned"}""",
            )
            val error =
                assertThrows(JourneyScenarioFormatException::class.java) {
                    JourneyScenarioParser.parse(file, pinned)
                }
            assertTrue(error.message.orEmpty().contains("exactly one hermes_revision"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun session_list_honors_the_requested_limit_and_reports_has_more() {
        val process = startGateway("session-list-first", tls = false)
        try {
            val endpoint = process.endpoint
            val page = Json.parseToJsonElement(getBody(endpoint, "/api/sessions?limit=1")).jsonObject
            assertEquals(1, page.getValue("data").jsonArray.size)
            assertEquals(1, page.getValue("limit").jsonPrimitive.int)
            assertEquals(0, page.getValue("offset").jsonPrimitive.int)
            assertEquals("true", page.getValue("has_more").jsonPrimitive.content)
        } finally {
            process.stop()
        }
    }

    @Test
    fun run_creation_requires_input_and_a_session_id() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            val missing = post(endpoint, "/v1/runs", """{"nope":true}""")
            assertEquals(400, missing.status)
            assertTrue(missing.body.contains("invalid-run-request"))
            val missingSession = post(endpoint, "/v1/runs", """{"input":"Run this"}""")
            assertEquals(400, missingSession.status)
            val empty = post(endpoint, "/v1/runs", """{"input":"","session_id":"session-alpha"}""")
            assertEquals(202, empty.status)
        } finally {
            process.stop()
        }
    }

    @Test
    fun empty_observation_with_a_final_status_reports_the_terminal_state() {
        val file = File.createTempFile("journey-scenario", ".json")
        try {
            val revision = pinnedDescriptor.provenance.value
            file.writeText(
                """
                {
                  "name": "empty-observation",
                  "hermes_revision": "$revision",
                  "port": 18443,
                  "tls": false,
                  "capabilities": ["sessions", "session_create", "session", "session_update", "session_delete", "session_messages", "runs", "run_status", "run_events"],
                  "sessions": [
                    {"id": "session-alpha", "title": "Alpha Session", "preview": "Synthetic preview", "pinned": false}
                  ],
                  "runs": [
                    {
                      "run_id": "empty-run",
                      "session_id": "session-alpha",
                      "create_status": "started",
                      "observation": [],
                      "final_status": "completed",
                      "terminal_history": [
                        {"id": "message-empty", "role": "assistant", "content": "Empty result"}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
            val scenario = JourneyScenarioParser.parse(file, revision)
            val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
            try {
                val endpoint = process.endpoint
                val create = post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
                assertEquals(202, create.status)
                assertEquals(emptyList<SseEvent>(), streamEvents(endpoint, "empty-run"))
                val status = Json.parseToJsonElement(getBody(endpoint, "/v1/runs/empty-run")).jsonObject
                assertEquals("completed", status.getValue("status").jsonPrimitive.content)
                val history = Json.parseToJsonElement(getBody(endpoint, "/api/sessions/session-alpha/messages")).jsonObject
                val contents =
                    history.getValue("data").jsonArray.map { message ->
                        message.jsonObject.getValue("content").jsonPrimitive.content
                    }
                assertTrue(contents.contains("Empty result"))
            } finally {
                process.stop()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun interrupted_stream_aborts_without_terminal_and_status_reports_final_state() {
        val process = startGateway("interrupted-sse-refetch", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            val events = streamEvents(endpoint, "interrupted-run")
            assertEquals(listOf("tool.started", "message.delta"), events.map { it.type })
            val status = get(endpoint, "/v1/runs/interrupted-run")
            assertTrue(status.contains("failed"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun held_open_stream_keeps_the_connection_until_the_client_closes() {
        val process = startGateway("streaming", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            val connection = (endpoint.resolve("/v1/runs/streaming-run/events").toURL().openConnection() as HttpURLConnection)
            connection.connectTimeout = 2_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            val lines = connection.inputStream.bufferedReader().lineSequence().iterator()
            val seen = mutableListOf<String>()
            while (lines.hasNext() && seen.none { it.contains("\"event\":\"message.delta\"") } && seen.size < 200) {
                seen += lines.next()
            }
            assertTrue(seen.any { it.contains("\"event\":\"message.delta\"") })
            connection.disconnect()
            awaitCondition { process.isRunning }
            val telemetry = get(endpoint, "/__fixture/telemetry")
            assertTrue(telemetry.contains(""""opened":1"""))
        } finally {
            process.stop()
        }
    }

    @Test
    fun bearer_credential_is_enforced_for_gateway_routes() {
        val process = startGateway("connection", tls = false)
        try {
            val endpoint = process.endpoint
            assertEquals(401, get(endpoint, "/v1/capabilities"))
            assertEquals(200, get(endpoint, "/v1/capabilities", bearer = "synthetic-token"))
            assertEquals(200, get(endpoint, "/health"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun pagination_respects_the_configured_page_size() {
        val process = startGateway("pagination-search", tls = false)
        try {
            val endpoint = process.endpoint
            val first = get(endpoint, "/api/sessions")
            assertTrue(first.contains(""""has_more":true"""))
            assertTrue(first.contains("Search Alpha One"))
            assertFalse(first.contains("Search Gamma Three"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun single_shot_session_list_failure_recovers_on_the_next_request() {
        val process = startGateway("stale-recoverable", tls = false)
        try {
            val endpoint = process.endpoint
            assertEquals(503, get(endpoint, "/api/sessions"))
            assertEquals(200, get(endpoint, "/api/sessions"))
        } finally {
            process.stop()
        }
    }

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
    fun tls_mode_serves_https_with_the_committed_keystore() {
        val keystoreFile = File(repositoryRoot, "fixtures/hermes/journey-tls/journey-gateway.p12")
        val scenarioFile = File(scenariosDir, "terminal-success.json")
        val scenario =
            JourneyScenarioParser.parse(scenarioFile, pinnedDescriptor.provenance.value)
                .copy(port = freePort(), tls = true)
        val process = JourneyGatewayProcess.start(scenario, keystoreFile)
        try {
            val trustStore =
                KeyStore.getInstance("PKCS12").apply {
                    keystoreFile.inputStream().use { input -> load(input, "journey-fixture".toCharArray()) }
                }
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(trustStore)
            val sslContext =
                SSLContext.getInstance("TLS").apply {
                    init(null, trustManagerFactory.trustManagers, null)
                }
            val connection =
                process.endpoint.resolve("/health").toURL().openConnection() as HttpsURLConnection
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            assertEquals(200, connection.responseCode)
        } finally {
            process.stop()
        }
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

    @Test
    fun teardown_stops_the_process() {
        val process = startGateway("session-list-first", tls = false)
        process.stop()
        assertFalse(process.isRunning)
    }

    private fun startGateway(
        name: String,
        tls: Boolean,
    ): JourneyGatewayProcess {
        val scenarioFile = File(scenariosDir, "$name.json")
        val scenario = JourneyScenarioParser.parse(scenarioFile, pinnedDescriptor.provenance.value)
        return JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = tls))
    }

    private fun get(
        endpoint: URI,
        path: String,
        bearer: String? = null,
    ): String {
        val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return runCatching {
            val stream = if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream
            "status=${connection.responseCode} body=${stream.bufferedReader().use { it.readText() }}"
        }.getOrElse { error ->
            "status=${connection.responseCode} error=${error.message}"
        }
    }

    private fun getBody(
        endpoint: URI,
        path: String,
    ): String {
        val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun post(
        endpoint: URI,
        path: String,
        body: String,
    ): HttpResponse {
        val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val responseBody = stream.bufferedReader().use { it.readText() }
        return HttpResponse(connection.responseCode, responseBody)
    }

    private fun streamEvents(
        endpoint: URI,
        runId: String,
    ): List<SseEvent> {
        val connection = (endpoint.resolve("/v1/runs/$runId/events").toURL().openConnection() as HttpURLConnection)
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        val lines = connection.inputStream.bufferedReader().lineSequence().iterator()
        val events = mutableListOf<SseEvent>()
        var lineCount = 0
        while (lines.hasNext() && lineCount < 200) {
            val line = lines.next()
            lineCount++
            if (line.startsWith("data:")) {
                // The pinned writer carries the event type inside the JSON payload and never
                // writes a named `event:` line.
                val data = Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                events += SseEvent(data.getValue("event").jsonPrimitive.content, data.toString())
            }
        }
        return events
    }

    private fun tempScenario(content: String): File {
        val file = File.createTempFile("journey-scenario", ".json")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private data class HttpResponse(
        val status: Int,
        val body: String,
    )

    private data class SseEvent(
        val type: String,
        val data: String,
    )

    private fun assertEquals(
        expected: Int,
        actual: String,
    ) {
        org.junit.Assert.assertTrue("Expected $expected in: $actual", actual.startsWith("status=$expected "))
    }
}
