package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class JourneyGatewayProcessTest {
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
                    val expected = expectedEndpointRow(name, scenario.modelCatalogRow)
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
                val history =
                    Json.parseToJsonElement(getBody(endpoint, "/api/sessions/session-alpha/messages")).jsonObject
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
            val connection =
                (endpoint.resolve("/v1/runs/streaming-run/events").toURL().openConnection() as HttpURLConnection)
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
    fun live_framing_stream_emits_the_real_gateway_comment_and_id_field_lines() {
        val process = startGateway("live-streaming", tls = false)
        try {
            val endpoint = process.endpoint
            post(endpoint, "/v1/runs", """{"input":"Run this","session_id":"session-alpha"}""")
            val connection =
                (endpoint.resolve("/v1/runs/live-run/events").toURL().openConnection() as HttpURLConnection)
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            val lines = connection.inputStream.bufferedReader().lineSequence().toList()
            assertEquals(": open", lines.first())
            assertEquals(": stream closed", lines.last { it.isNotEmpty() })
            val dataIndexes =
                lines.mapIndexedNotNull { index, line -> if (line.startsWith("data:")) index else null }
            assertEquals(3, dataIndexes.size)
            dataIndexes.forEach { dataIndex ->
                val idLine = lines[dataIndex - 1]
                assertTrue("The data line at $dataIndex lacks a preceding id field.", idLine.startsWith("id:"))
            }
            assertEquals(listOf("id: 0", "id: 1", "id: 2"), dataIndexes.map { lines[it - 1] })
            assertTrue(lines.any { it.contains("\"event\":\"message.delta\"") })
            assertTrue(lines.any { it.contains("\"event\":\"run.completed\"") })
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
    fun offline_gate_fails_every_session_request_after_the_first_list() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"offline-gate","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","session"],"fail_after_first_session_list":true,""" +
                    """"sessions":[{"id":"session-alpha","title":"Alpha Session","preview":null,"pinned":false}]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
        try {
            val endpoint = process.endpoint
            assertEquals(200, get(endpoint, "/api/sessions"))
            assertEquals(503, get(endpoint, "/api/sessions"))
            assertEquals(503, get(endpoint, "/api/sessions/session-alpha"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun offline_gate_recovers_the_session_list_after_the_pinned_blocked_request_count() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"offline-recovery","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","session"],"fail_after_first_session_list":true,""" +
                    """"offline_recovers_after_blocked_session_lists":2,""" +
                    """"sessions":[{"id":"session-alpha","title":"Alpha Session","preview":null,"pinned":false}]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
        try {
            val endpoint = process.endpoint
            assertEquals(200, get(endpoint, "/api/sessions"))
            assertEquals(503, get(endpoint, "/api/sessions"))
            assertEquals(503, get(endpoint, "/api/sessions"))
            assertEquals(503, get(endpoint, "/api/sessions/session-alpha"))
            assertEquals(200, get(endpoint, "/api/sessions"))
            assertEquals(200, get(endpoint, "/api/sessions/session-alpha"))
            assertEquals(200, get(endpoint, "/api/sessions"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun offline_recovery_requires_the_offline_gate_to_be_armed() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"recovery-without-gate","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions"],""" +
                    """"offline_recovers_after_blocked_session_lists":2,"sessions":[]}""",
            )
        val error =
            runCatching { JourneyScenarioParser.parse(file, revision) }.exceptionOrNull()
        assertTrue(error is JourneyScenarioFormatException)
    }

    @Test
    fun open_gate_blocks_transcript_reads_after_the_first_and_recovers_after_the_pinned_blocked_count() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"open-gate","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","session","session_messages"],""" +
                    """"fail_after_first_session_open":true,""" +
                    """"offline_recovers_after_blocked_session_opens":1,""" +
                    """"sessions":[{"id":"session-alpha","title":"Alpha Session","preview":null,"pinned":false,""" +
                    """"history":[{"id":"message-alpha-1","role":"user",""" +
                    """"content":"Synthetic cached conversation"}]}]}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
        try {
            val endpoint = process.endpoint
            assertEquals(200, get(endpoint, "/api/sessions/session-alpha/messages"))
            assertEquals(503, get(endpoint, "/api/sessions/session-alpha/messages"))
            assertEquals(200, get(endpoint, "/api/sessions/session-alpha"))
            assertEquals(200, get(endpoint, "/api/sessions"))
            assertEquals(200, get(endpoint, "/api/sessions/session-alpha/messages"))
        } finally {
            process.stop()
        }
    }

    @Test
    fun held_create_response_is_recorded_before_the_bounded_release() {
        val revision = pinnedDescriptor.provenance.value
        val file =
            tempScenario(
                """{"name":"held-create","hermes_revision":"$revision","port":18443,"tls":false,""" +
                    """"capabilities":["sessions","session_create"],"hold_create_ms":1000}""",
            )
        val scenario = JourneyScenarioParser.parse(file, revision)
        val process = JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = false))
        try {
            val endpoint = process.endpoint
            get(endpoint, "/api/sessions")
            val result = java.util.concurrent.atomic.AtomicReference<HttpResponse>()
            val start = System.nanoTime()
            val thread = Thread { result.set(post(endpoint, "/api/sessions", """{"title":"Pending draft"}""")) }
            thread.start()
            Thread.sleep(250)
            val duringHold = getBody(endpoint, "/__fixture/telemetry")
            assertTrue(
                "The create request was not recorded before the hold.",
                duringHold.contains("\"method\":\"POST\"") && duringHold.contains("\"path\":\"/api/sessions\""),
            )
            assertNull("The held create response was released before the bounded hold.", result.get())
            thread.join(5_000)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val released = result.get()
            assertNotNull("The held create response was never released.", released)
            assertEquals(201, released!!.status)
            assertTrue("The held response was released after ${elapsedMs}ms, before the 1000ms hold.", elapsedMs >= 950)
            assertTrue(released.body.contains("synthetic-created-session-1"))
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
    fun teardown_stops_the_process() {
        val process = startGateway("session-list-first", tls = false)
        process.stop()
        assertFalse(process.isRunning)
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun advertised_journey_gateway_serves_the_model_catalog_and_records_run_selections() {
        val process = startGateway("model-picker-advertised", tls = false)
        try {
            val endpoint = process.endpoint
            assertEquals(401, get(endpoint, "/api/models"))
            val catalog = get(endpoint, "/api/models", bearer = "synthetic-token")
            assertEquals(200, catalog)
            val body = Json.parseToJsonElement(catalog.substringAfter(" body=")).jsonObject
            assertEquals("hermes.api_server.run_models", body.getValue("object").jsonPrimitive.content)
            assertEquals("deepseek-v4-flash", body.getValue("default_model").jsonPrimitive.content)
            val models = body.getValue("models").jsonArray
            assertEquals(2, models.size)
            val first = models[0].jsonObject
            assertEquals("DeepSeek V4 Flash", first.getValue("display_name").jsonPrimitive.content)
            assertEquals(
                listOf("low", "medium", "high"),
                first.getValue("effort").jsonObject.getValue("supported").jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertEquals("medium", first.getValue("effort").jsonObject.getValue("default").jsonPrimitive.content)
            val second = models[1].jsonObject
            assertEquals("hermes-agent", second.getValue("id").jsonPrimitive.content)
            assertFalse(second.containsKey("effort"))
            val created =
                post(
                    endpoint,
                    "/v1/runs",
                    """{"input":"Rely on this","session_id":"session-alpha","model":"hermes-agent"}""",
                    bearer = "synthetic-token",
                )
            assertEquals(202, created.status)
            val telemetry = get(endpoint, "/__fixture/telemetry")
            assertTrue(
                telemetry.contains(
                    "\"model\":\"hermes-agent\",\"effort\":null," +
                        "\"accepted\":true",
                ),
            )
        } finally {
            process.stop()
        }
    }

    @Test
    fun refusal_script_answers_admission_with_the_openai_error_envelope_and_records_the_pair() {
        val process = startGateway("model-picker-refusal", tls = false)
        try {
            val endpoint = process.endpoint
            val refused =
                post(
                    endpoint,
                    "/v1/runs",
                    """{"input":"Refuse this","session_id":"session-alpha",""" +
                        """"model":"deepseek-v4-flash","effort":"high"}""",
                )
            assertEquals(400, refused.status)
            assertTrue(refused.body.contains("\"code\":\"unsupported_model\""))
            assertTrue(refused.body.contains("\"type\":\"invalid_request_error\""))
            val retried = post(endpoint, "/v1/runs", """{"input":"Refuse this","session_id":"session-alpha"}""")
            assertEquals(202, retried.status)
            val telemetry = get(endpoint, "/__fixture/telemetry")
            assertTrue(
                telemetry.contains(
                    "\"model\":\"deepseek-v4-flash\",\"effort\":\"high\",\"accepted\":false",
                ),
            )
            assertTrue(
                telemetry.contains("\"model\":null,\"effort\":null,\"accepted\":true"),
            )
        } finally {
            process.stop()
        }
    }

    @Test
    fun mismatched_row_is_advertised_and_the_absent_scenario_serves_no_catalog_route() {
        val mismatch = startGateway("model-catalog-row-mismatch", tls = false)
        try {
            val capabilities = get(mismatch.endpoint, "/v1/capabilities")
            assertEquals(200, capabilities)
            assertTrue(
                capabilities.contains("\"run_models\":{\"method\":\"GET\",\"path\":\"/v1/models\"}"),
            )
        } finally {
            mismatch.stop()
        }
        val absent = startGateway("model-picker-absent", tls = false)
        try {
            assertEquals(404, get(absent.endpoint, "/api/models"))
            val capabilities = get(absent.endpoint, "/v1/capabilities")
            assertTrue(!capabilities.contains("run_models"))
            assertTrue(capabilities.contains("\"sessions\""))
        } finally {
            absent.stop()
        }
    }

    private fun assertEquals(
        expected: Int,
        actual: String,
    ) {
        org.junit.Assert.assertTrue("Expected $expected in: $actual", actual.startsWith("status=$expected "))
    }

    private fun expectedEndpointRow(
        name: String,
        override: JourneyModelCatalogRow?,
    ): JourneyEndpointCatalog.Endpoint =
        if (name == "run_models" && override != null) {
            JourneyEndpointCatalog.Endpoint(override.method, override.path)
        } else {
            JourneyEndpointCatalog.endpoints.getValue(name)
        }
}
