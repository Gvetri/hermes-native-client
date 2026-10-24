package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class JourneyCacheLifecycleVerifierTest {
    @Test
    fun cache_lifecycle_verifiers_accept_get_only_telemetry_with_the_required_reads() {
        JourneyInvariants.verify(
            "settings-cache-clear",
            cacheLifecycleTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions/session-alpha",
                    ),
            ),
        )
        JourneyInvariants.verify(
            "connection-removal-purge",
            cacheLifecycleTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "GET" to "/api/sessions",
                    ),
            ),
        )
        JourneyInvariants.verify(
            "cache-clear-repopulates",
            cacheLifecycleTelemetry(
                requests =
                    buildList {
                        add("GET" to "/v1/capabilities")
                        repeat(4) { add("GET" to "/api/sessions") }
                        repeat(4) { add("GET" to "/api/sessions/session-alpha") }
                    },
            ),
        )
    }

    @Test
    fun cache_lifecycle_verifiers_reject_gateway_writes_and_run_creations() {
        val mutated =
            cacheLifecycleTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                        "PATCH" to "/api/sessions/session-alpha",
                    ),
            )
        val writeError =
            runCatching { JourneyInvariants.verify("settings-cache-clear", mutated) }.exceptionOrNull()
        checkNotNull(writeError) { "The cache clear verifier accepted a Gateway mutation." }
        check(writeError.message!!.contains("mutated Gateway state")) {
            "Unexpected cache verifier failure: ${writeError.message}"
        }

        val runCreated =
            cacheLifecycleTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                    ),
                runCreates = mapOf("session-alpha" to 1),
            )
        val runError =
            runCatching { JourneyInvariants.verify("connection-removal-purge", runCreated) }.exceptionOrNull()
        checkNotNull(runError) { "The removal purge verifier accepted a Run creation." }
        check(runError.message!!.contains("recorded a Run creation")) {
            "Unexpected removal purge verifier failure: ${runError.message}"
        }
    }

    @Test
    fun cache_lifecycle_verifiers_require_their_distinctive_reads() {
        val noListRead =
            cacheLifecycleTelemetry(requests = listOf("GET" to "/v1/capabilities"))
        checkNotNull(
            runCatching { JourneyInvariants.verify("settings-cache-clear", noListRead) }.exceptionOrNull(),
        ) { "The cache clear verifier accepted a journey without a Session list read." }

        val singleListRead =
            cacheLifecycleTelemetry(
                requests =
                    listOf(
                        "GET" to "/v1/capabilities",
                        "GET" to "/api/sessions",
                    ),
            )
        val purgeError =
            runCatching { JourneyInvariants.verify("connection-removal-purge", singleListRead) }.exceptionOrNull()
        checkNotNull(purgeError) { "The removal purge verifier accepted a journey without a reconnect reload." }
        check(purgeError.message!!.contains("reloaded the Session list")) {
            "Unexpected removal purge verifier failure: ${purgeError.message}"
        }

        val repopulateError =
            runCatching { JourneyInvariants.verify("cache-clear-repopulates", singleListRead) }.exceptionOrNull()
        checkNotNull(repopulateError) {
            "The repopulation verifier accepted fewer reads than the offline arm scripts."
        }
        check(repopulateError.message!!.contains("retry the Session list")) {
            "Unexpected repopulation verifier failure: ${repopulateError.message}"
        }
    }

    private fun cacheLifecycleTelemetry(
        requests: List<Pair<String, String>>,
        runCreates: Map<String, Int> = emptyMap(),
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
            if (runCreates.isNotEmpty()) {
                put(
                    "run_creates",
                    buildJsonObject {
                        runCreates.forEach { (session, count) -> put(session, count) }
                    },
                )
            }
        }
}
