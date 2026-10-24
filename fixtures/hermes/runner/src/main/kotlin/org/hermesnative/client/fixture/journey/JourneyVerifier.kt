package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

internal data class RunSelectionTelemetry(
    val sessionId: String,
    val model: String?,
    val effort: String?,
    val accepted: Boolean,
)

private fun catalogRequests(telemetry: JsonObject): List<String> =
    telemetry["requests"]?.jsonArray.orEmpty().mapNotNull { request ->
        val fields = request.jsonObject
        val path = fields["path"]?.jsonPrimitive?.content
        path?.takeIf { it == JourneyModelPickerInvariants.MODEL_CATALOG_PATH }
    }

private fun runSelections(telemetry: JsonObject): List<RunSelectionTelemetry> =
    telemetry["run_selections"]?.jsonArray?.map { entry ->
        val fields = entry.jsonObject
        RunSelectionTelemetry(
            sessionId = fields.getValue("session_id").jsonPrimitive.content,
            model = fields["model"]?.let(::stringOrNull),
            effort = fields["effort"]?.let(::stringOrNull),
            accepted = fields.getValue("accepted").jsonPrimitive.content.toBooleanStrict(),
        )
    } ?: error("Telemetry has no run-selection records.")

private fun stringOrNull(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive
    return primitive?.takeUnless { it is JsonNull }?.content
}

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
    private val scenarioVerifiers: Map<String, (JsonObject) -> Unit> =
        mapOf(
            "connection" to ::requireConnectionAttempted,
            "session-list-first" to ::requireConnectionAttempted,
            "empty-sessions" to JourneySessionCreationInvariants::verifyEmptySessions,
            "pending-creation" to JourneySessionCreationInvariants::verifyPendingCreation,
            "session-lifecycle" to JourneySessionLifecycleInvariants::verifySessionLifecycle,
            "pagination-search" to ::requireConnectionAttempted,
            "failed-offline-send" to JourneyRunInvariants::verifyFailedOfflineSend,
            "offline-session-list" to JourneyInvariants::verifyOfflineSessionList,
            "markdown-links" to ::requireConnectionAttempted,
            "accessibility-controls" to ::requireConnectionAttempted,
            "capabilities-additive" to JourneyInvariants::verifyCapabilitiesAdditive,
            "capabilities-missing-required" to ::requireConnectionAttempted,
            "active-run-isolation" to JourneyRunInvariants::verifyActiveRunIsolation,
            "streaming" to JourneyRunInvariants::verifyStreaming,
            "live-streaming" to JourneyRunInvariants::verifyLiveStreaming,
            "interrupted-sse-refetch" to JourneyRunInvariants::verifyInterruptedSseRefetch,
            "composer-stop" to JourneyRunInvariants::verifyComposerStop,
            "terminal-success" to JourneyRunInvariants::verifyTerminal,
            "terminal-failure" to JourneyRunInvariants::verifyTerminal,
            "explicit-retry" to JourneyRunInvariants::verifyExplicitRetry,
            "confirmed-send" to JourneyRunInvariants::verifyConfirmedSend,
            "markdown-blocks" to ::requireConnectionAttempted,
            "stale-recoverable" to ::requireConnectionAttempted,
            "diagnostics-recording" to JourneyDiagnosticsInvariants::verifyDiagnosticsRecording,
            "settings" to JourneyInvariants::verifySettings,
            "settings-cache-clear" to JourneyInvariants::verifySettingsCacheClear,
            "connection-removal-purge" to JourneyInvariants::verifyConnectionRemovalPurge,
            "cache-clear-repopulates" to JourneyInvariants::verifyCacheClearRepopulates,
            "offline-cache-read" to JourneyInvariants::verifyOfflineCacheRead,
            "cache-run-isolation" to JourneyInvariants::verifyCacheRunIsolation,
            "model-picker-advertised" to JourneyModelPickerInvariants::verifyModelPickerAdvertised,
            "model-picker-absent" to JourneyModelPickerInvariants::verifyModelPickerAbsent,
            "model-picker-refusal" to JourneyModelPickerInvariants::verifyModelPickerRefusal,
            "model-catalog-row-mismatch" to JourneyModelPickerInvariants::verifyModelCatalogRowMismatch,
        )

    fun verify(
        scenarioName: String,
        telemetry: JsonObject,
    ) {
        scenarioVerifiers[scenarioName]?.invoke(telemetry)
            ?: error("No journey invariants declared for scenario '$scenarioName'.")
    }

    private fun verifySettings(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val requests =
            telemetry.getValue("requests").jsonArray.map { request ->
                val fields = request.jsonObject
                fields.getValue("method").jsonPrimitive.content to fields.getValue("path").jsonPrimitive.content
            }
        val writes = requests.filter { (method, _) -> method != "GET" }
        check(writes.isEmpty()) {
            "The Settings journey mutated Gateway state: $writes"
        }
        check(requests.any { (method, path) -> method == "GET" && path == "/api/sessions" }) {
            "The Settings journey never loaded the Session list."
        }
        val runCreates = telemetry["run_creates"]?.jsonObject.orEmpty()
        check(runCreates.values.all { it.jsonPrimitive.int == 0 }) {
            "The Settings journey recorded a Run creation: $runCreates"
        }
    }

    private fun verifyOfflineSessionList(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val paths = recordedRequestPaths(telemetry)
        check(paths.count { it == "/api/sessions" } >= 2) {
            "The client did not retry the Session list while the Gateway was offline."
        }
        check(paths.any { it.startsWith("/api/sessions/") }) {
            "The client did not attempt to open a Session while the Gateway was offline."
        }
    }

    private fun requireCacheLifecycleIsLocalOnly(
        telemetry: JsonObject,
        label: String,
    ) {
        requireConnectionAttempted(telemetry)
        val requests =
            telemetry.getValue("requests").jsonArray.map { request ->
                val fields = request.jsonObject
                fields.getValue("method").jsonPrimitive.content to fields.getValue("path").jsonPrimitive.content
            }
        val writes = requests.filter { (method, _) -> method != "GET" }
        check(writes.isEmpty()) {
            "The $label journey mutated Gateway state: $writes"
        }
        val runCreates = telemetry["run_creates"]?.jsonObject.orEmpty()
        check(runCreates.values.all { it.jsonPrimitive.int == 0 }) {
            "The $label journey recorded a Run creation: $runCreates"
        }
    }

    private fun verifySettingsCacheClear(telemetry: JsonObject) {
        requireCacheLifecycleIsLocalOnly(telemetry, "Settings cache clear")
        check(recordedRequestPaths(telemetry).any { it == "/api/sessions" }) {
            "The Settings cache clear journey never loaded the Session list."
        }
    }

    private fun verifyConnectionRemovalPurge(telemetry: JsonObject) {
        requireCacheLifecycleIsLocalOnly(telemetry, "Connection removal purge")
        check(recordedRequestPaths(telemetry).count { it == "/api/sessions" } >= 2) {
            "The Connection removal purge journey never reloaded the Session list after reconnecting."
        }
    }

    private fun verifyCacheClearRepopulates(telemetry: JsonObject) {
        requireCacheLifecycleIsLocalOnly(telemetry, "Cache clear repopulation")
        val paths = recordedRequestPaths(telemetry)
        check(paths.count { it == "/api/sessions" } >= MIN_CACHE_REPOPULATION_LIST_READS) {
            "The Cache clear repopulation journey did not retry the Session list enough times: $paths"
        }
        check(paths.count { it.startsWith("/api/sessions/") } >= MIN_CACHE_REPOPULATION_RESOURCE_READS) {
            "The Cache clear repopulation journey did not read Session content through the offline and recovered arms."
        }
    }

    private fun verifyOfflineCacheRead(telemetry: JsonObject) {
        requireCacheLifecycleIsLocalOnly(telemetry, "Offline cache read")
        val paths = recordedRequestPaths(telemetry)
        check(paths.count { it == "/api/sessions" } >= MIN_OFFLINE_CACHE_READ_LIST_READS) {
            "The Offline cache read journey did not exercise the Session list offline and recovered arms: $paths"
        }
        check(paths.count { it.endsWith("/messages") } >= MIN_OFFLINE_CACHE_READ_TRANSCRIPT_READS) {
            "The Offline cache read journey did not attempt the Session transcript offline: $paths"
        }
    }

    private fun verifyCacheRunIsolation(telemetry: JsonObject) {
        JourneyRunInvariants.verifyFailedOfflineSend(telemetry)
        val paths = recordedRequestPaths(telemetry)
        check(paths.count { it.endsWith("/messages") } >= MIN_RUN_ISOLATION_TRANSCRIPT_READS) {
            "The Run isolation journey did not read the Session transcript around the restart: $paths"
        }
        check(telemetry["session_mutations"]?.jsonArray.orEmpty().isEmpty()) {
            "The Run isolation journey mutated Session state beyond the scripted Runs."
        }
    }

    private fun verifyCapabilitiesAdditive(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runCreatesFor(telemetry, ACTIVE_SESSION_ID) == 1) {
            "The client submitted the additive Run more than once."
        }
    }

    const val ACTIVE_RUN_ID = "active-run"
    const val ACTIVE_SESSION_ID = "session-alpha"
    const val STREAMING_RUN_ID = "streaming-run"
    const val MIN_CACHE_REPOPULATION_LIST_READS = 4
    const val MIN_CACHE_REPOPULATION_RESOURCE_READS = 4
    const val MIN_OFFLINE_CACHE_READ_LIST_READS = 3
    const val MIN_OFFLINE_CACHE_READ_TRANSCRIPT_READS = 3
    const val MIN_RUN_ISOLATION_TRANSCRIPT_READS = 2
    const val LIVE_RUN_ID = "live-run"
    const val STOP_RUN_ID = "stop-run"
    const val INTERRUPTED_RUN_ID = "interrupted-run"
    const val RETRY_SESSION_ID = "session-retry"
    const val CONFIRMED_SEND_SESSION_ID = "session-ux"
    const val CONFIRMED_SEND_RUN_ID = "run-ux-1"
}

private object JourneySessionCreationInvariants {
    internal fun verifyEmptySessions(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val requests =
            telemetry.getValue("requests").jsonArray.map { request ->
                val fields = request.jsonObject
                fields.getValue("method").jsonPrimitive.content to fields.getValue("path").jsonPrimitive.content
            }
        val writes = requests.filter { (method, _) -> method != "GET" }
        check(writes == listOf("POST" to "/api/sessions")) {
            "The confirmed creation and its pre-submit dismissal did not write exactly one Session create: $writes"
        }
        val operations =
            telemetry.getValue("session_mutations").jsonArray.map { entry ->
                val fields = entry.jsonObject
                fields.getValue("operation").jsonPrimitive.content to
                    fields.getValue("session_id").jsonPrimitive.content
            }
        check(operations == listOf("create" to "synthetic-created-session-1")) {
            "The confirmed creation did not record exactly one create operation: $operations"
        }
        val runCreates = telemetry["run_creates"]?.jsonObject.orEmpty()
        check(runCreates.isEmpty()) { "The empty-sessions journey recorded a Run creation: $runCreates" }
        var writesSeen = 0
        val checkpoints =
            buildList {
                requests.forEach { (method, path) ->
                    if (method != "GET") {
                        writesSeen += 1
                    } else {
                        when (path) {
                            "/api/sessions" -> add("list" to writesSeen)
                        }
                    }
                }
            }
        check(checkpoints == listOf("list" to 0, "list" to 0, "list" to 1)) {
            "Empty-session cancellation checkpoints recorded missing reads or premature writes: $checkpoints"
        }
    }

    internal fun verifyPendingCreation(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val requests =
            telemetry.getValue("requests").jsonArray.map { request ->
                val fields = request.jsonObject
                fields.getValue("method").jsonPrimitive.content to fields.getValue("path").jsonPrimitive.content
            }
        val writes = requests.filter { (method, _) -> method != "GET" }
        check(writes == listOf("POST" to "/api/sessions")) {
            "The pending creation did not resolve to exactly one Session create: $writes"
        }
        val operations =
            telemetry.getValue("session_mutations").jsonArray.map { entry ->
                val fields = entry.jsonObject
                fields.getValue("operation").jsonPrimitive.content to
                    fields.getValue("session_id").jsonPrimitive.content
            }
        check(operations == listOf("create" to "synthetic-created-session-1")) {
            "The pending creation did not record exactly one create operation: $operations"
        }
        check(requests.none { (_, path) -> path == "/v1/runs" || path.startsWith("/v1/runs/") }) {
            "The pending creation recorded Run traffic: $requests"
        }
        check(runStatusRequests(telemetry) == 0) {
            "The pending creation read Run status ${runStatusRequests(telemetry)} times; expected 0."
        }
        val runCreates = telemetry["run_creates"]?.jsonObject.orEmpty()
        check(runCreates.isEmpty()) { "The pending creation recorded a Run creation: $runCreates" }
        val sse = telemetry["sse_connections"]?.jsonObject.orEmpty()
        check(sse.isEmpty()) { "The pending creation opened a Run observation: $sse" }
        var writesSeen = 0
        val checkpoints =
            buildList {
                requests.forEach { (method, path) ->
                    if (method != "GET") {
                        writesSeen += 1
                    } else {
                        when (path) {
                            "/api/sessions" -> add("list" to writesSeen)
                        }
                    }
                }
            }
        check(checkpoints == listOf("list" to 0, "list" to 1)) {
            "Pending-creation reads recorded a dismissal reaction or a premature write: $checkpoints"
        }
    }
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
            "list" to 0,
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

    internal fun verifyLiveStreaming(telemetry: JsonObject) {
        val sse = sseFor(telemetry, JourneyInvariants.LIVE_RUN_ID)
        check(sse.opened == 1) { "Live-framed Run was observed ${sse.opened} times; expected exactly 1." }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the live-framed Run." }
        check(runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID) == 1) {
            "The client submitted the live-framed Run more than once."
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

    internal fun verifyComposerStop(telemetry: JsonObject) {
        val sse = sseFor(telemetry, JourneyInvariants.STOP_RUN_ID)
        check(sse.opened == 1) { "Stopped Run was observed ${sse.opened} times; expected exactly 1." }
        check(sse.closed == 1) {
            "Stopped Run observation closed ${sse.closed} times; expected exactly 1 (the composer stop)."
        }
        check(JourneyInvariants.STOP_RUN_ID !in interruptedRunIds(telemetry)) {
            "The Gateway interrupted the Run; the client did not stop it."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the Run after stopping." }
        check(runCreatesFor(telemetry, JourneyInvariants.ACTIVE_SESSION_ID) == 1) {
            "The client submitted the stopped Run more than once."
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

    internal fun verifyFailedOfflineSend(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runCreatesFor(telemetry, OFFLINE_SEND_SESSION_ID) == 2) {
            "The client did not submit exactly two Run creations (one refused while offline plus one recovery retry)."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the recovered Run." }
    }

    internal fun verifyConfirmedSend(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(runCreatesFor(telemetry, JourneyInvariants.CONFIRMED_SEND_SESSION_ID) == 1) {
            "The confirmed send did not submit exactly one Run creation."
        }
        check(runStatusRequests(telemetry) >= 1) { "The client did not reconcile the confirmed Run." }
        val sse = sseFor(telemetry, JourneyInvariants.CONFIRMED_SEND_RUN_ID)
        check(sse.opened >= 1) { "The client did not observe the confirmed Run." }
    }

    private const val OFFLINE_SEND_SESSION_ID = "session-offline"
}

private object JourneyDiagnosticsInvariants {
    internal fun verifyDiagnosticsRecording(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val loadedSessionList =
            telemetry["requests"]?.jsonArray.orEmpty().any { request ->
                val fields = request.jsonObject
                fields["method"]?.jsonPrimitive?.content == "GET" &&
                    fields["path"]?.jsonPrimitive?.content == "/api/sessions"
            }
        check(loadedSessionList) { "The diagnostics journey never loaded the Session list." }
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

private object JourneyModelPickerInvariants {
    const val MODEL_CATALOG_PATH = "/api/models"
    const val CATALOG_SESSION_ID = "session-alpha"
    const val CATALOG_MODEL_ID = "deepseek-v4-flash"
    const val SECOND_MODEL_ID = "hermes-agent"

    internal fun verifyModelPickerAdvertised(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        val fetches = catalogRequests(telemetry)
        check(fetches.size == EXPECTED_ADVERTISED_FETCHES) {
            "The advertised catalog was fetched ${fetches.size} times; " +
                "expected $EXPECTED_ADVERTISED_FETCHES (one per verified connection)."
        }
        check(
            runSelections(telemetry) ==
                listOf(
                    RunSelectionTelemetry(CATALOG_SESSION_ID, SECOND_MODEL_ID, null, accepted = true),
                    RunSelectionTelemetry(CATALOG_SESSION_ID, CATALOG_MODEL_ID, "low", accepted = true),
                ),
        ) {
            "The advertised picker submissions did not carry the scripted overrides: ${runSelections(telemetry)}"
        }
        check(runCreatesFor(telemetry, CATALOG_SESSION_ID) == 2) {
            "The advertised picker journey did not submit exactly two Runs."
        }
        check(runStatusRequests(telemetry) >= 2) {
            "The client did not reconcile both terminal Runs of the advertised picker journey."
        }
    }

    internal fun verifyModelPickerAbsent(telemetry: JsonObject) {
        verifyNoCatalogTraffic(telemetry)
        check(runCreatesFor(telemetry, CATALOG_SESSION_ID) == 1) {
            "The absent-capability journey did not submit exactly one Run."
        }
    }

    internal fun verifyModelPickerRefusal(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(catalogRequests(telemetry).size == 1) {
            "The refusal journey fetched the catalog ${catalogRequests(telemetry).size} times; expected 1."
        }
        check(
            runSelections(telemetry) ==
                listOf(
                    RunSelectionTelemetry(CATALOG_SESSION_ID, CATALOG_MODEL_ID, "high", accepted = false),
                    RunSelectionTelemetry(CATALOG_SESSION_ID, null, null, accepted = true),
                ),
        ) {
            "The refusal journey did not record a refused selection followed by an accepted retry: " +
                runSelections(telemetry)
        }
        check(runCreatesFor(telemetry, CATALOG_SESSION_ID) == 2) {
            "The refusal journey did not submit exactly two Run creations (one refused, one retried)."
        }
    }

    internal fun verifyModelCatalogRowMismatch(telemetry: JsonObject) {
        verifyNoCatalogTraffic(telemetry)
        check(runCreatesFor(telemetry, CATALOG_SESSION_ID) == 1) {
            "The mismatched-row journey did not submit exactly one Run."
        }
    }

    private fun verifyNoCatalogTraffic(telemetry: JsonObject) {
        requireConnectionAttempted(telemetry)
        check(catalogRequests(telemetry).isEmpty()) {
            "The client fetched the model catalog although the capability row was not advertised as usable."
        }
        val selections = runSelections(telemetry)
        check(selections == listOf(RunSelectionTelemetry(CATALOG_SESSION_ID, null, null, accepted = true))) {
            "The no-catalog journey submitted an unexpected Run selection: $selections"
        }
    }

    private const val EXPECTED_ADVERTISED_FETCHES = 2
}
