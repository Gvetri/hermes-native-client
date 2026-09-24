package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Asserts gateway-side invariants after one Maestro journey.
 *
 * The checks are repository-owned and fail closed: every journey must declare
 * its invariants here, and an unknown journey name is a verification failure.
 *
 * Arguments: <telemetry-url> <scenario-name> [keystore-file] [keystore-password]
 */
fun main(args: Array<String>) {
    require(args.size in 2..4) { "Usage: JourneyVerifier <telemetry-url> <scenario-name> [keystore-file] [keystore-password]" }
    val telemetryUrl = URI.create(args[0])
    val scenarioName = args[1]
    val keystoreFile = args.getOrNull(2)?.let(::File)
    val keystorePassword = args.getOrNull(3) ?: DEFAULT_KEYSTORE_PASSWORD
    val telemetry = fetchTelemetry(telemetryUrl, keystoreFile, keystorePassword)
    JourneyInvariants.verify(scenarioName, telemetry)
    if (scenarioName == "active-run-isolation") {
        // The remote Run must still be running after the switch away and back;
        // the live status is read from the still-running fake Gateway.
        val run =
            fetchTelemetry(
                telemetryUrl.resolve("/v1/runs/${JourneyInvariants.ACTIVE_RUN_ID}"),
                keystoreFile,
                keystorePassword,
            )
        check(run["status"]?.jsonPrimitive?.content == "running") {
            "The active Run did not remain running after the Session switch."
        }
    }
    println("journey-verifier=pass")
}

internal data class RunConnectionTelemetry(
    val opened: Int,
    val closed: Int,
)

internal fun fetchTelemetry(
    telemetryUrl: URI,
    keystoreFile: File? = null,
    keystorePassword: String = DEFAULT_KEYSTORE_PASSWORD,
): JsonObject {
    val connection =
        if (telemetryUrl.scheme == "https") {
            val keyStore =
                KeyStore.getInstance("PKCS12").apply {
                    requireNotNull(keystoreFile).inputStream().use { input -> load(input, keystorePassword.toCharArray()) }
                }
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)
            val sslContext =
                SSLContext.getInstance("TLS").apply {
                    init(null, trustManagerFactory.trustManagers, null)
                }
            (telemetryUrl.toURL().openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = sslContext.socketFactory
            }
        } else {
            telemetryUrl.toURL().openConnection() as HttpURLConnection
        }
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.requestMethod = "GET"
    // Telemetry reads are infrastructure probes; the fixture serves them
    // without recording them as application traffic.
    connection.setRequestProperty("X-Journey-Probe", "verifier")
    if (connection is HttpsURLConnection) {
        // The journey certificate is fixed to 127.0.0.1/10.0.2.2 loopback SANs.
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
    return try {
        check(connection.responseCode == 200) { "Telemetry endpoint returned HTTP ${connection.responseCode}." }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(body).jsonObject
    } finally {
        connection.disconnect()
    }
}

internal object JourneyInvariants {
    fun verify(
        scenarioName: String,
        telemetry: JsonObject,
    ) {
        when (scenarioName) {
            "connection" -> verifyConnection(telemetry)
            "session-list-first" -> verifySessionListFirst(telemetry)
            "empty-sessions" -> verifySessionListFirst(telemetry)
            "session-lifecycle" -> verifySessionLifecycle(telemetry)
            "pagination-search" -> verifyPaginationSearch(telemetry)
            "active-run-isolation" -> verifyActiveRunIsolation(telemetry)
            "streaming" -> verifyStreaming(telemetry)
            "interrupted-sse-refetch" -> verifyInterruptedSseRefetch(telemetry)
            "terminal-success" -> verifyTerminal(telemetry)
            "terminal-failure" -> verifyTerminal(telemetry)
            "explicit-retry" -> verifyExplicitRetry(telemetry)
            "stale-recoverable" -> verifyStaleRecoverable(telemetry)
            "markdown-links" -> verifyMarkdownLinks(telemetry)
            "capabilities-additive" -> verifyCapabilitiesBoundary(telemetry)
            "capabilities-missing-required" -> verifyCapabilitiesBoundary(telemetry)
            "accessibility-controls" -> verifyAccessibilityControls(telemetry)
            else -> error("No journey invariants declared for scenario '$scenarioName'.")
        }
    }

    private fun sseFor(
        telemetry: JsonObject,
        runId: String,
    ): RunConnectionTelemetry {
        val run =
            telemetry["sse_connections"]?.jsonObject?.get(runId)?.jsonObject
                ?: error("Telemetry has no SSE connection record for run '$runId'.")
        return RunConnectionTelemetry(
            opened = run.getValue("opened").jsonPrimitive.int,
            closed = run.getValue("closed").jsonPrimitive.int,
        )
    }

    private fun runCreatesFor(
        telemetry: JsonObject,
        sessionId: String,
    ): Int =
        telemetry["run_creates"]?.jsonObject?.get(sessionId)?.jsonPrimitive?.int
            ?: error("Telemetry has no run-create record for session '$sessionId'.")

    private fun runStatusRequests(telemetry: JsonObject): Int = telemetry.getValue("run_status_requests").jsonPrimitive.int

    private fun interruptedRunIds(telemetry: JsonObject): Set<String> =
        telemetry["interrupted_run_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun requireConnectionAttempted(telemetry: JsonObject) {
        val requests = telemetry["requests"]?.jsonArray.orEmpty()
        check(requests.isNotEmpty()) { "The journey Gateway recorded no application requests." }
        val paths = recordedRequestPaths(telemetry)
        check(paths.any { it.startsWith("/v1/") }) {
            "The application never issued a Gateway API request."
        }
    }

    private fun recordedRequestPaths(telemetry: JsonObject): List<String> =
        telemetry["requests"]?.jsonArray.orEmpty().mapNotNull { request ->
            request.jsonObject["path"]?.jsonPrimitive?.content
        }

    private fun recordedRequestMethods(telemetry: JsonObject): List<String> =
        telemetry["requests"]?.jsonArray.orEmpty().mapNotNull { request ->
            request.jsonObject["method"]?.jsonPrimitive?.content
        }

    private fun verifyConnection(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifySessionListFirst(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifySessionLifecycle(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyPaginationSearch(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyActiveRunIsolation(telemetry: JsonObject) {
        // The journey observes the active Run once, refreshes history (which
        // must not open a duplicate SSE observation), switches to another
        // Session (which must not cancel the Run), and returns (re-observing
        // exactly once). Opened == 2 proves the refresh added no duplicate;
        // closed == 1 proves only the Session switch closed the stream.
        val sse = sseFor(telemetry, ACTIVE_RUN_ID)
        check(sse.opened == 2) {
            "Active Run was observed ${sse.opened} times; expected 2 (initial plus re-open, no refresh duplicate)."
        }
        check(sse.closed == 1) { "Active Run observation closed ${sse.closed} times; expected exactly 1 (Session switch)." }
        check(runStatusRequests(telemetry) >= 1) { "The client did not re-check the active Run status." }
        check(runCreatesFor(telemetry, ACTIVE_SESSION_ID) == 1) {
            "The client submitted the active Run more than once."
        }
        check(recordedRequestPaths(telemetry).none { it.contains("cancel", ignoreCase = true) }) {
            "The client attempted to cancel the active Run while switching Sessions."
        }
        check(recordedRequestMethods(telemetry).none { it.equals("DELETE", ignoreCase = true) }) {
            "The client issued a DELETE request while switching Sessions."
        }
    }

    private fun verifyStreaming(telemetry: JsonObject) {
        val sse = sseFor(telemetry, STREAMING_RUN_ID)
        check(sse.opened == 1) { "Streaming Run was observed ${sse.opened} times; expected exactly 1." }
        check(runCreatesFor(telemetry, ACTIVE_SESSION_ID) == 1) {
            "The client submitted the streaming Run more than once."
        }
    }

    private fun verifyInterruptedSseRefetch(telemetry: JsonObject) {
        val sse = sseFor(telemetry, INTERRUPTED_RUN_ID)
        check(sse.opened == 1) { "Interrupted Run was observed ${sse.opened} times; expected exactly 1." }
        check(INTERRUPTED_RUN_ID in interruptedRunIds(telemetry)) { "The Gateway did not record the interrupted Run." }
        check(runStatusRequests(telemetry) >= 1) { "The client did not refetch authoritative Run status." }
        check(runCreatesFor(telemetry, ACTIVE_SESSION_ID) == 1) {
            "The client re-submitted the interrupted Run."
        }
    }

    private fun verifyTerminal(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the terminal Run." }
        val submissions = runCreatesFor(telemetry, ACTIVE_SESSION_ID)
        check(submissions == 1) {
            "The client submitted the terminal Run $submissions times; expected exactly 1 (no duplicate retry submissions)."
        }
    }

    private fun verifyExplicitRetry(telemetry: JsonObject) {
        check(runCreatesFor(telemetry, RETRY_SESSION_ID) == 2) {
            "The client did not submit exactly two Run creations (initial run plus one retry)."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile Run status." }
    }

    private fun verifyStaleRecoverable(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyMarkdownLinks(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyCapabilitiesBoundary(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyAccessibilityControls(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    const val ACTIVE_RUN_ID = "active-run"
    const val ACTIVE_SESSION_ID = "session-alpha"
    const val STREAMING_RUN_ID = "streaming-run"
    const val INTERRUPTED_RUN_ID = "interrupted-run"
    const val RETRY_SESSION_ID = "session-retry"
}

private const val DEFAULT_KEYSTORE_PASSWORD = "journey-fixture"
