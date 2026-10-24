package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class JourneyOfflineCacheReadVerifierTest {
    @Test
    fun offline_cache_read_verifier_accepts_the_journeys_get_only_read_pattern() {
        JourneyInvariants.verify(
            "offline-cache-read",
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-alpha",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                    ),
            ),
        )
    }

    @Test
    fun offline_cache_read_verifier_requires_every_offline_and_recovered_arm() {
        val shallowList =
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha/messages",
                    ),
            )
        val listError =
            runCatching { JourneyInvariants.verify("offline-cache-read", shallowList) }.exceptionOrNull()
        checkNotNull(listError) { "The offline cache read verifier accepted fewer than three list reads." }
        check(listError.message!!.contains("list")) { "Unexpected offline cache read failure: ${listError.message}" }

        val shallowTranscript =
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha/messages",
                    ),
            )
        val transcriptError =
            runCatching { JourneyInvariants.verify("offline-cache-read", shallowTranscript) }.exceptionOrNull()
        checkNotNull(transcriptError) { "The offline cache read verifier accepted fewer than three transcript reads." }
        check(transcriptError.message!!.contains("transcript")) {
            "Unexpected offline cache read failure: ${transcriptError.message}"
        }
    }

    @Test
    fun offline_cache_read_verifier_rejects_gateway_mutations() {
        val mutated =
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "GET" to "/api/sessions/session-alpha/messages",
                        "PATCH" to "/api/sessions/session-alpha",
                    ),
            )
        val error = runCatching { JourneyInvariants.verify("offline-cache-read", mutated) }.exceptionOrNull()
        checkNotNull(error) { "The offline cache read verifier accepted a Gateway mutation." }
        check(error.message!!.contains("mutated Gateway state")) { "Unexpected failure: ${error.message}" }
    }

    @Test
    fun cache_run_isolation_verifier_accepts_the_cacheless_twin_pattern() {
        JourneyInvariants.verify(
            "cache-run-isolation",
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-offline",
                        "GET" to "/api/sessions/session-offline/messages",
                        "POST" to "/v1/runs",
                        "POST" to "/v1/runs",
                        "GET" to "/v1/runs/run-offline-recovered",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-offline",
                        "GET" to "/api/sessions/session-offline/messages",
                        "GET" to "/v1/runs/run-offline-recovered",
                    ),
                runCreates = mapOf("session-offline" to 2),
                runStatusRequests = 2,
            ),
        )
    }

    @Test
    fun cache_run_isolation_verifier_rejects_session_mutations_and_missing_transcript_reads() {
        val mutatedSessions =
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-offline",
                        "GET" to "/api/sessions/session-offline/messages",
                        "POST" to "/v1/runs",
                        "POST" to "/v1/runs",
                        "GET" to "/api/sessions/session-offline",
                        "GET" to "/api/sessions/session-offline/messages",
                    ),
                runCreates = mapOf("session-offline" to 2),
                runStatusRequests = 1,
                sessionMutations = listOf("rename" to "session-offline"),
            )
        val mutationError =
            runCatching { JourneyInvariants.verify("cache-run-isolation", mutatedSessions) }.exceptionOrNull()
        checkNotNull(mutationError) { "The Run isolation verifier accepted a Session mutation." }
        check(mutationError.message!!.contains("mutated Session state")) {
            "Unexpected Run isolation failure: ${mutationError.message}"
        }

        val missingTranscript =
            cacheReadTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "POST" to "/v1/runs",
                        "POST" to "/v1/runs",
                        "GET" to "/api/sessions",
                    ),
                runCreates = mapOf("session-offline" to 2),
                runStatusRequests = 1,
            )
        val transcriptError =
            runCatching { JourneyInvariants.verify("cache-run-isolation", missingTranscript) }.exceptionOrNull()
        checkNotNull(transcriptError) { "The Run isolation verifier accepted no transcript reads." }
        check(transcriptError.message!!.contains("transcript")) {
            "Unexpected Run isolation failure: ${transcriptError.message}"
        }
    }

    private fun cacheReadTelemetry(
        requests: List<Pair<String, String>>,
        runCreates: Map<String, Int> = emptyMap(),
        runStatusRequests: Int = 0,
        sessionMutations: List<Pair<String, String>> = emptyList(),
    ): JsonObject =
        buildJsonObject {
            put(
                "requests",
                buildJsonArray {
                    requests.forEach { (method, path) ->
                        add(
                            buildJsonObject {
                                put("method", method)
                                put("path", path)
                            },
                        )
                    }
                },
            )
            put(
                "run_creates",
                buildJsonObject {
                    runCreates.forEach { (session, count) -> put(session, count) }
                },
            )
            put("run_status_requests", runStatusRequests)
            put(
                "session_mutations",
                buildJsonArray {
                    sessionMutations.forEach { (operation, sessionId) ->
                        add(
                            buildJsonObject {
                                put("operation", operation)
                                put("session_id", sessionId)
                            },
                        )
                    }
                },
            )
        }
}
