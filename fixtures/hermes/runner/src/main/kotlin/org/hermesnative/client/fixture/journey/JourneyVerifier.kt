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
    require(args.size in MIN_ARGUMENT_COUNT..MAX_ARGUMENT_COUNT) {
        "Usage: JourneyVerifier <telemetry-url> <scenario-name> [keystore-file] [keystore-password]"
    }
    val telemetryUrl = URI.create(args[0])
    val scenarioName = args[1]
    val keystoreFile = args.getOrNull(2)?.let(::File)
    val keystorePassword = args.getOrNull(KEYSTORE_PASSWORD_ARG_INDEX) ?: DEFAULT_KEYSTORE_PASSWORD
    val telemetry = fetchTelemetry(telemetryUrl, keystoreFile, keystorePassword)
    JourneyInvariants.verify(scenarioName, telemetry)
    if (scenarioName == "active-run-isolation") {
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
                    requireNotNull(keystoreFile).inputStream()
                        .use { input -> load(input, keystorePassword.toCharArray()) }
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
    connection.connectTimeout = TELEMETRY_TIMEOUT_MILLIS
    connection.readTimeout = TELEMETRY_TIMEOUT_MILLIS
    connection.requestMethod = "GET"
    connection.setRequestProperty("X-Journey-Probe", "verifier")
    if (connection is HttpsURLConnection) {
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
    return try {
        check(connection.responseCode == HTTP_OK) { "Telemetry endpoint returned HTTP ${connection.responseCode}." }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(body).jsonObject
    } finally {
        connection.disconnect()
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

private fun runStatusRequests(telemetry: JsonObject): Int {
    return telemetry.getValue("run_status_requests").jsonPrimitive.int
}

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

internal object JourneyInvariants {
    fun verify(
        scenarioName: String,
        telemetry: JsonObject,
    ) {
        when (scenarioName) {
            "connection" -> verifyConnection(telemetry)
            "session-list-first" -> verifySessionListFirst(telemetry)
            "empty-sessions" -> verifySessionListFirst(telemetry)
            "session-lifecycle" -> JourneySessionLifecycleInvariants.verifySessionLifecycle(telemetry)
            "pagination-search" -> verifyPaginationSearch(telemetry)
            "stale-recoverable" -> verifyStaleRecoverable(telemetry)
            "markdown-links" -> verifyMarkdownLinks(telemetry)
            "accessibility-controls" -> verifyAccessibilityControls(telemetry)
            "capabilities-additive" -> verifyCapabilitiesAdditive(telemetry)
            "capabilities-missing-required" -> verifyCapabilitiesBoundary(telemetry)
            else -> verifyAdditionalScenario(scenarioName, telemetry)
        }
    }

    private fun verifyAdditionalScenario(
        scenarioName: String,
        telemetry: JsonObject,
    ) {
        when (scenarioName) {
            "active-run-isolation" -> JourneyRunInvariants.verifyActiveRunIsolation(telemetry)
            "streaming" -> JourneyRunInvariants.verifyStreaming(telemetry)
            "interrupted-sse-refetch" -> JourneyRunInvariants.verifyInterruptedSseRefetch(telemetry)
            "terminal-success" -> JourneyRunInvariants.verifyTerminal(telemetry)
            "terminal-failure" -> JourneyRunInvariants.verifyTerminal(telemetry)
            "explicit-retry" -> JourneyRunInvariants.verifyExplicitRetry(telemetry)
            else -> error("No journey invariants declared for scenario '$scenarioName'.")
        }
    }

    private fun verifyConnection(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifySessionListFirst(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
    }

    private fun verifyPaginationSearch(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
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

    private fun verifyCapabilitiesAdditive(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runCreatesFor(telemetry, ACTIVE_SESSION_ID) == 1) {
            "The client submitted the additive Run more than once."
        }
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

private object JourneySessionLifecycleInvariants {
    private val expectedSessionMutations =
        listOf(
            "POST" to "/api/sessions",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "PATCH" to "/api/sessions/synthetic-created-session-1",
            "DELETE" to "/api/sessions/synthetic-created-session-1",
            "PATCH" to "/api/sessions/session-alpha",
            "PATCH" to "/api/sessions/session-alpha",
            "PATCH" to "/api/sessions/session-alpha",
            "DELETE" to "/api/sessions/session-alpha",
        )

    private val expectedSessionOperations =
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

    private val expectedSessionCheckpoints =
        listOf(
            "list" to 0,
            "history" to 1,
            "list" to 1,
            "history" to 1,
            "list" to LIST_CHECKPOINT_AFTER_CREATED_SESSION_PIN,
            "list" to LIST_CHECKPOINT_AFTER_CREATED_SESSION_UNPIN,
            "list" to LIST_CHECKPOINT_AFTER_CREATED_SESSION_DELETE,
            "list" to LIST_CHECKPOINT_AFTER_ALPHA_SESSION_PIN,
            "list" to LIST_CHECKPOINT_AFTER_ALPHA_SESSION_UNPIN,
        )

    internal fun verifySessionLifecycle(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val requests = recordedRequests(telemetry)
        val mutations = requests.filter { (method, _) -> method != "GET" }
        check(mutations == expectedSessionMutations) {
            "Session management writes did not match the explicitly confirmed journey actions: $mutations"
        }
        val operations = recordedSessionOperations(telemetry)
        check(operations == expectedSessionOperations) {
            "The confirmed Session operation types or targets did not match the journey."
        }
        val checkpoints = sessionReadCheckpoints(requests)
        check(checkpoints == expectedSessionCheckpoints) {
            "Session cancellation checkpoints recorded missing reads or premature writes: $checkpoints"
        }
    }

    private fun recordedRequests(telemetry: JsonObject): List<Pair<String, String>> =
        telemetry.getValue("requests").jsonArray.map { request ->
            val fields = request.jsonObject
            fields.getValue("method").jsonPrimitive.content to fields.getValue("path").jsonPrimitive.content
        }

    private fun recordedSessionOperations(telemetry: JsonObject): List<Pair<String, String>> =
        telemetry.getValue("session_mutations").jsonArray.map { entry ->
            val fields = entry.jsonObject
            fields.getValue("operation").jsonPrimitive.content to fields.getValue("session_id").jsonPrimitive.content
        }

    private fun sessionReadCheckpoints(requests: List<Pair<String, String>>): List<Pair<String, Int>> {
        var writesSeen = 0
        return buildList {
            requests.forEach { (method, path) ->
                if (method != "GET") {
                    writesSeen += 1
                } else {
                    when (path) {
                        "/api/sessions" -> add("list" to writesSeen)
                        "/api/sessions/synthetic-created-session-1/messages" -> add("history" to writesSeen)
                    }
                }
            }
        }
    }
}

private object JourneyRunInvariants {
    internal fun verifyActiveRunIsolation(telemetry: JsonObject) {
        val sse = sseFor(telemetry, JourneyInvariants.ACTIVE_RUN_ID)
        check(sse.opened == 2) {
            "Active Run was observed ${sse.opened} times; expected 2 (initial plus re-open, no refresh duplicate)."
        }
        check(sse.closed == 1) {
            "Active Run observation closed ${sse.closed} times; expected exactly 1 (Session switch)."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not re-check the active Run status." }
        check(runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID) == 1) {
            "The client submitted the active Run more than once."
        }
        check(recordedRequestPaths(telemetry).none { it.contains("cancel", ignoreCase = true) }) {
            "The client attempted to cancel the active Run while switching Sessions."
        }
        check(recordedRequestMethods(telemetry).none { it.equals("DELETE", ignoreCase = true) }) {
            "The client issued a DELETE request while switching Sessions."
        }
    }

    internal fun verifyStreaming(telemetry: JsonObject) {
        val sse = sseFor(telemetry, JourneyInvariants.STREAMING_RUN_ID)
        check(sse.opened == 1) { "Streaming Run was observed ${sse.opened} times; expected exactly 1." }
        check(runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID) == 1) {
            "The client submitted the streaming Run more than once."
        }
    }

    internal fun verifyInterruptedSseRefetch(telemetry: JsonObject) {
        val sse = sseFor(telemetry, JourneyInvariants.INTERRUPTED_RUN_ID)
        check(sse.opened == 1) { "Interrupted Run was observed ${sse.opened} times; expected exactly 1." }
        check(JourneyInvariants.INTERRUPTED_RUN_ID in interruptedRunIds(telemetry)) {
            "The Gateway did not record the interrupted Run."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not refetch authoritative Run status." }
        check(runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID) == 1) {
            "The client re-submitted the interrupted Run."
        }
    }

    internal fun verifyTerminal(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the terminal Run." }
        val submissions = runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID)
        check(submissions == 1) {
            "The client submitted the terminal Run $submissions times; " +
                "expected exactly 1 (no duplicate retry submissions)."
        }
    }

    internal fun verifyExplicitRetry(telemetry: JsonObject) {
        check(runCreatesFor(telemetry, JourneyInvariants.RETRY_SESSION_ID) == 2) {
            "The client did not submit exactly two Run creations (initial run plus one explicit retry)."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile Run status." }
    }
}

private const val MIN_ARGUMENT_COUNT = 2
private const val MAX_ARGUMENT_COUNT = 4
private const val KEYSTORE_PASSWORD_ARG_INDEX = 3
private const val TELEMETRY_TIMEOUT_MILLIS = 5_000
private const val HTTP_OK = 200
private const val LIST_CHECKPOINT_AFTER_CREATED_SESSION_PIN = 3
private const val LIST_CHECKPOINT_AFTER_CREATED_SESSION_UNPIN = 4
private const val LIST_CHECKPOINT_AFTER_CREATED_SESSION_DELETE = 5
private const val LIST_CHECKPOINT_AFTER_ALPHA_SESSION_PIN = 7
private const val LIST_CHECKPOINT_AFTER_ALPHA_SESSION_UNPIN = 8
