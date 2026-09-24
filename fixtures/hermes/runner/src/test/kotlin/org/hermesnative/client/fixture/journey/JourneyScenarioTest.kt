package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
    fun session_list_response_includes_all_fixture_sessions_and_nullable_updated_at() {
        val process = startGateway("session-list-first", tls = false)
        try {
            val page = Json.parseToJsonElement(getBody(process.endpoint, "/v1/sessions")).jsonObject
            val sessions = page.getValue("sessions").jsonArray
            assertEquals(2, sessions.size)
            sessions.forEach { session -> assertEquals(JsonNull, session.jsonObject["updated_at"]) }
        } finally {
            process.stop()
        }
    }

    @Test
    fun empty_session_list_returns_a_successful_empty_page() {
        val process = startGateway("empty-sessions", tls = false)
        try {
            assertEquals(
                """status=200 body={"sessions":[],"next_cursor":null}""",
                get(process.endpoint, "/v1/sessions"),
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
                    """"port":18443,"tls":false,"capabilities":["client-manifest"],"mutable":"latest"}""",
            )
        val error = runCatching { JourneyScenarioParser.parse(file, pinnedDescriptor.provenance.value) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
        assertTrue(error!!.message.orEmpty().contains("unsupported"))
    }

    @Test
    fun interrupted_and_held_open_run_scripts_are_rejected() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """
                {"name":"invalid-run","hermes_revision":"$revision","port":18443,"tls":false,"capabilities":["client-manifest"],
                 "sessions":[{"id":"s1","title":"S","preview":null,"pinned":false}],
                 "runs":[{"run_id":"r1","session_id":"s1","hold_open":true,"interrupt_after_events":1,"observation":[{"type":"run.started","status":"starting"}]}]}
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
            assertTrue(capabilities.contains("session.list"))

            val sessions = get(endpoint, "/v1/sessions")
            assertTrue(sessions.contains("Alpha Session"))

            val create = post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
            assertEquals(202, create.status)
            assertTrue(create.body.contains("run-success"))

            val events = streamEvents(endpoint, "run-success")
            assertEquals(listOf("run.started", "run.running", "run.succeeded"), events.map { it.type })

            assertEquals(200, get(endpoint, "/v1/runs/run-success"))
            val statusBody = get(endpoint, "/v1/runs/run-success")
            assertTrue(statusBody.contains("succeeded"))

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
            post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
            val events = streamEvents(endpoint, "run-success")
            assertEquals(3, events.size)
            val history = getBody(endpoint, "/v1/sessions/session-alpha/history")
            assertTrue(history.contains("message-success"))
            assertTrue(history.contains("Stable result"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun run_submissions_beyond_the_scripted_runs_are_refused_and_counted() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
            streamEvents(endpoint, "run-success")
            val resubmit = post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
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
    fun session_list_honors_the_requested_limit() {
        val process = startGateway("session-list-first", tls = false)
        try {
            val endpoint = process.endpoint
            val page = Json.parseToJsonElement(getBody(endpoint, "/v1/sessions?limit=1")).jsonObject
            assertEquals(1, page.getValue("sessions").jsonArray.size)
            assertEquals("offset:1", page.getValue("next_cursor").jsonPrimitive.content)
        } finally {
            process.stop()
        }
    }

    @Test
    fun run_creation_requires_an_input_field() {
        val process = startGateway("terminal-success", tls = false)
        try {
            val endpoint = process.endpoint
            val missing = post(endpoint, "/v1/sessions/session-alpha/runs", """{"nope":true}""")
            assertEquals(400, missing.status)
            assertTrue(missing.body.contains("invalid-run-input"))
            val empty = post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":""}""")
            assertEquals(202, empty.status)
        } finally {
            process.stop()
        }
    }

    @Test
    fun interrupted_stream_aborts_without_terminal_and_status_reports_final_state() {
        val process = startGateway("interrupted-sse-refetch", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
            val events = streamEvents(endpoint, "interrupted-run")
            assertEquals(listOf("run.started", "run.running", "message.delta"), events.map { it.type })
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
            post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
            val connection = (endpoint.resolve("/v1/runs/streaming-run/events").toURL().openConnection() as HttpURLConnection)
            connection.connectTimeout = 2_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            val lines = connection.inputStream.bufferedReader().lineSequence().iterator()
            val seen = mutableListOf<String>()
            while (lines.hasNext() && seen.none { it.startsWith("event: message.delta") } && seen.size < 200) {
                seen += lines.next()
            }
            assertTrue(seen.any { it.startsWith("event: message.delta") })
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
            val first = get(endpoint, "/v1/sessions")
            assertTrue(first.contains("next_cursor"))
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
            assertEquals(503, get(endpoint, "/v1/sessions"))
            assertEquals(200, get(endpoint, "/v1/sessions"))
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
            post(endpoint, "/v1/sessions/session-alpha/runs", """{"input":"Run this"}""")
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
        var type: String? = null
        var lineCount = 0
        while (lines.hasNext() && lineCount < 200) {
            val line = lines.next()
            lineCount++
            when {
                line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                line.startsWith("data:") -> events += SseEvent(requireNotNull(type), line.removePrefix("data:").trim())
                line.isEmpty() -> type = null
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
