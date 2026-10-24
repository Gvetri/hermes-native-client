package org.hermesnative.client.fixture.journey

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.hermesnative.client.fixture.GatewayHttpSupport
import org.hermesnative.client.fixture.GatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayRequest
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_ACCEPTED = 202
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val DEFAULT_PAGE_LIMIT = 50
internal const val DEFAULT_KEYSTORE_PASSWORD = "journey-fixture"
private const val HOLD_OPEN_KEEPALIVE_MILLIS = 500L
private val keepAliveComment: ByteArray = ": keep-alive\n\n".toByteArray(Charsets.UTF_8)
private val streamOpenComment: ByteArray = ": open\n\n".toByteArray(Charsets.UTF_8)
private val streamClosedComment: ByteArray = ": stream closed\n\n".toByteArray(Charsets.UTF_8)

/**
 * The controlled fake Gateway for deterministic emulator journeys.
 *
 * Starts from an explicit repository-owned [JourneyScenario], serves synthetic
 * session and Run data over HTTP or TLS, exposes deterministic SSE observation,
 * records request/observation telemetry, and verifies clean teardown. It never
 * contacts a public endpoint and uses no real provider credentials.
 *
 * The served shapes follow the pinned Hermes revision: `/v1/capabilities` advertises
 * an `endpoints` table, session resources live under `/api/sessions`, Run admission is
 * `POST /v1/runs`, and Run SSE frames carry the event type inside the JSON `data:`
 * payload without a named `event:` line.
 */
class JourneyGatewayProcess private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
    override val endpoint: URI,
    override val provenanceValue: String,
) : GatewayProcess {
    @Volatile
    private var serverStopped = false

    @Volatile
    override var isRunning: Boolean = true
        private set

    override fun stop() {
        if (!isRunning) {
            return
        }
        if (!serverStopped) {
            server.stop(0)
            serverStopped = true
        }
        executor.shutdownNow()
        check(executor.awaitTermination(1, TimeUnit.SECONDS)) {
            "Journey Gateway executor did not stop."
        }
        isRunning = false
    }

    companion object {
        fun start(
            scenario: JourneyScenario,
            keystoreFile: File? = null,
            keystorePassword: String = DEFAULT_KEYSTORE_PASSWORD,
        ): JourneyGatewayProcess {
            val server: HttpServer
            val executor = Executors.newCachedThreadPool()
            if (scenario.tls) {
                val https = HttpsServer.create(InetSocketAddress("127.0.0.1", scenario.port), 0)
                val keyStore =
                    loadKeyStore(
                        requireNotNull(keystoreFile) { "Journey TLS scenarios require a keystore." },
                        keystorePassword,
                    )
                https.httpsConfigurator = HttpsConfigurator(serverSslContext(keyStore, keystorePassword))
                https.executor = executor
                server = https
            } else {
                val http = HttpServer.create(InetSocketAddress("127.0.0.1", scenario.port), 0)
                http.executor = executor
                server = http
            }
            val behavior = JourneyGatewayBehavior(scenario)
            registerRoutes(server, scenario, behavior)
            server.start()
            val scheme = if (scenario.tls) "https" else "http"
            return JourneyGatewayProcess(
                server = server,
                executor = executor,
                endpoint = URI.create("$scheme://127.0.0.1:${server.address.port}"),
                provenanceValue = scenario.hermesRevision,
            )
        }

        private fun registerRoutes(
            server: HttpServer,
            scenario: JourneyScenario,
            behavior: JourneyGatewayBehavior,
        ) {
            server.createContext("/health") { exchange ->
                record(exchange, behavior)
                respond(exchange, HTTP_OK, """{"status":"synthetic"}""")
            }
            server.createContext("/__fixture/telemetry") { exchange ->
                record(exchange, behavior)
                respond(exchange, HTTP_OK, behavior.telemetryJson())
            }
            server.createContext("/v1/capabilities") { exchange ->
                record(exchange, behavior)
                if (unauthorized(exchange, behavior)) {
                    respond(exchange, HTTP_UNAUTHORIZED, """{"error":"unauthorized"}""")
                } else {
                    respond(exchange, HTTP_OK, JourneyGatewayJson.capabilitiesJson(scenario))
                }
            }
            server.createContext("/api/sessions") { exchange ->
                JourneyGatewayRoutes.handleSessionList(exchange, behavior)
            }
            server.createContext("/api/sessions/") { exchange ->
                JourneyGatewayRoutes.handleSessionResource(exchange, behavior)
            }
            if (scenario.modelCatalog != null) {
                server.createContext("/api/models") { exchange ->
                    JourneyGatewayRoutes.handleModelCatalog(exchange, behavior)
                }
            }
            server.createContext("/v1/runs") { exchange ->
                JourneyGatewayRoutes.handleRuns(exchange, behavior)
            }
            server.createContext("/") { exchange ->
                record(exchange, behavior)
                respond(exchange, HTTP_NOT_FOUND, """{"error":"not-found"}""")
            }
        }

        private fun loadKeyStore(
            keystoreFile: File,
            keystorePassword: String,
        ): KeyStore {
            require(keystoreFile.isFile) { "Journey TLS keystore does not exist: ${keystoreFile.absolutePath}" }
            return KeyStore.getInstance("PKCS12").apply {
                keystoreFile.inputStream().use { input ->
                    load(input, keystorePassword.toCharArray())
                }
            }
        }

        private fun serverSslContext(
            keyStore: KeyStore,
            keystorePassword: String,
        ): SSLContext {
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, keystorePassword.toCharArray())
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)
            return SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
            }
        }
    }
}

private fun unauthorized(
    exchange: HttpExchange,
    behavior: JourneyGatewayBehavior,
): Boolean {
    val required = behavior.scenario.requireBearerCredential ?: return false
    val authorization = exchange.requestHeaders.getFirst("Authorization")
    return authorization != "Bearer $required"
}

private fun record(
    exchange: HttpExchange,
    behavior: JourneyGatewayBehavior,
): String? = GatewayHttpSupport.recordRequest(exchange, behavior.requests)

private fun respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    GatewayHttpSupport.respond(exchange, status, body)
}

private fun String?.jsonValue(): String = GatewayHttpSupport.jsonValue(this)

private object JourneyGatewayRoutes {
    internal fun handleSessionList(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        val body = record(exchange, behavior)
        if (behavior.offlineGate.onSessionListRequest()) {
            respond(exchange, HTTP_SERVICE_UNAVAILABLE, """{"error":"synthetic-offline-failure"}""")
            return
        }
        if (unauthorized(exchange, behavior)) {
            respond(exchange, HTTP_UNAUTHORIZED, """{"error":"unauthorized"}""")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> respondSessionListPage(exchange, behavior)
            "POST" -> {
                behavior.scenario.holdCreateMs?.let { holdMillis -> Thread.sleep(holdMillis.toLong()) }
                val created =
                    JourneySession(
                        id = behavior.createdSessionId(),
                        title = JourneyGatewayBodyJson.parseTitle(body),
                        preview = null,
                        pinned = false,
                        history = emptyList(),
                    )
                behavior.sessions.removeIf { it.id == created.id }
                behavior.sessions += created
                behavior.sessionMutations += "create" to created.id
                respond(
                    exchange,
                    HTTP_CREATED,
                    """{"object":"hermes.session","session":${JourneyGatewayJson.sessionJson(created)}}""",
                )
            }
            else -> respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"method-not-allowed"}""")
        }
    }

    private fun respondSessionListPage(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        val query = GatewayHttpSupport.queryParameters(exchange.requestURI)
        val requestedLimit = query["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
        val offset = query["offset"]?.toIntOrNull() ?: 0
        val matching = behavior.sessions.toList()
        when {
            behavior.consumeFailNextSessionList() ->
                respond(exchange, HTTP_SERVICE_UNAVAILABLE, """{"error":"synthetic-refresh-failure"}""")
            requestedLimit <= 0 || offset < 0 ->
                respond(exchange, HTTP_BAD_REQUEST, """{"error":"invalid-pagination"}""")
            offset > matching.size ->
                respond(exchange, HTTP_BAD_REQUEST, """{"error":"offset-out-of-range"}""")
            else -> {
                val serverCap = behavior.scenario.sessionPageSize ?: requestedLimit
                val effectiveLimit = minOf(requestedLimit, serverCap)
                val page = matching.drop(offset).take(effectiveLimit)
                val hasMore = offset + page.size < matching.size
                val sessionsJson = page.joinToString(",") { JourneyGatewayJson.sessionJson(it) }
                respond(
                    exchange,
                    HTTP_OK,
                    """{"object":"list","data":[$sessionsJson],""" +
                        """"limit":$effectiveLimit,"offset":$offset,"has_more":$hasMore}""",
                )
            }
        }
    }

    internal fun handleSessionResource(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        val body = record(exchange, behavior)
        val offline = behavior.offlineGate.blocking
        if (offline || unauthorized(exchange, behavior)) {
            if (offline) {
                respond(exchange, HTTP_SERVICE_UNAVAILABLE, """{"error":"synthetic-offline-failure"}""")
            } else {
                respond(exchange, HTTP_UNAUTHORIZED, """{"error":"unauthorized"}""")
            }
            return
        }
        val resource = exchange.requestURI.path.removePrefix("/api/sessions/")
        val segments = resource.split('/')
        val sessionId = segments.firstOrNull().orEmpty()
        val session = behavior.sessions.firstOrNull { it.id == sessionId }
        if (session == null || segments.size !in 1..2) {
            respond(exchange, HTTP_NOT_FOUND, """{"error":"session-not-found"}""")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> respondSessionResourceGet(exchange, behavior, session, segments)
            "PATCH" -> respondSessionResourcePatch(exchange, behavior, session, segments, body)
            "DELETE" -> respondSessionResourceDelete(exchange, behavior, session.id, segments)
            else -> respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"method-not-allowed"}""")
        }
    }

    private fun respondSessionResourceGet(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        session: JourneySession,
        segments: List<String>,
    ) {
        when {
            segments.size == 1 ->
                respond(
                    exchange,
                    HTTP_OK,
                    """{"object":"hermes.session","session":${JourneyGatewayJson.sessionJson(session)}}""",
                )

            segments[1] == "messages" && behavior.offlineOpenGate.onSessionHistoryRequest() ->
                respond(exchange, HTTP_SERVICE_UNAVAILABLE, """{"error":"synthetic-offline-failure"}""")

            segments[1] == "messages" ->
                respond(exchange, HTTP_OK, JourneyGatewayJson.historyJson(behavior, session))

            else -> respond(exchange, HTTP_NOT_FOUND, """{"error":"not-found"}""")
        }
    }

    private fun respondSessionResourcePatch(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        session: JourneySession,
        segments: List<String>,
        body: String?,
    ) {
        if (segments.size != 1) {
            respond(exchange, HTTP_NOT_FOUND, """{"error":"not-found"}""")
            return
        }
        val pinned = JourneyGatewayBodyJson.parseBooleanField(body, "pinned")
        val updated =
            if (pinned != null) {
                session.copy(pinned = pinned)
            } else {
                session.copy(title = JourneyGatewayBodyJson.parseTitle(body))
            }
        behavior.replaceSession(updated)
        val operation =
            if (pinned == null) {
                "rename"
            } else if (pinned) {
                "pin"
            } else {
                "unpin"
            }
        behavior.sessionMutations += operation to session.id
        respond(
            exchange,
            HTTP_OK,
            """{"object":"hermes.session","session":${JourneyGatewayJson.sessionJson(updated)}}""",
        )
    }

    private fun respondSessionResourceDelete(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        sessionId: String,
        segments: List<String>,
    ) {
        if (segments.size != 1) {
            respond(exchange, HTTP_NOT_FOUND, """{"error":"not-found"}""")
            return
        }
        behavior.sessions.removeIf { it.id == sessionId }
        behavior.sessionMutations += "delete" to sessionId
        respond(
            exchange,
            HTTP_OK,
            """{"object":"hermes.session.deleted","id":${sessionId.jsonValue()},"deleted":true}""",
        )
    }

    internal fun handleModelCatalog(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        record(exchange, behavior)
        if (unauthorized(exchange, behavior)) {
            respond(exchange, HTTP_UNAUTHORIZED, """{"error":"unauthorized"}""")
            return
        }
        when {
            exchange.requestURI.path != "/api/models" ->
                respond(exchange, HTTP_NOT_FOUND, """{"error":"not-found"}""")

            exchange.requestMethod != "GET" ->
                respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"method-not-allowed"}""")

            else -> respond(exchange, HTTP_OK, JourneyGatewayJson.catalogJson(behavior.scenario))
        }
    }

    internal fun handleRuns(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        val body = record(exchange, behavior)
        if (unauthorized(exchange, behavior)) {
            respond(exchange, HTTP_UNAUTHORIZED, """{"error":"unauthorized"}""")
            return
        }
        when {
            exchange.requestURI.path != "/v1/runs" -> handleRunResource(exchange, behavior)
            exchange.requestMethod != "POST" ->
                respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"method-not-allowed"}""")
            else -> handleRunCreate(exchange, behavior, body)
        }
    }

    private fun handleRunCreate(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        body: String?,
    ) {
        val sessionId = JourneyGatewayBodyJson.parseStringField(body, "session_id")
        if (sessionId == null || JourneyGatewayBodyJson.parseRunInput(body) == null) {
            respond(exchange, HTTP_BAD_REQUEST, """{"error":"invalid-run-request"}""")
            return
        }
        if (behavior.sessions.none { it.id == sessionId }) {
            respond(exchange, HTTP_NOT_FOUND, """{"error":"session-not-found"}""")
            return
        }
        val model = JourneyGatewayBodyJson.parseStringField(body, "model")
        val effort = JourneyGatewayBodyJson.parseStringField(body, "effort")
        val script = behavior.nextRunScript(sessionId)
        if (script == null) {
            behavior.recordSelection(sessionId, model, effort, accepted = false)
            respond(exchange, HTTP_NOT_FOUND, """{"error":"no-synthetic-run"}""")
        } else if (script.refuseCreate) {
            behavior.recordSelection(sessionId, model, effort, accepted = false)
            respond(exchange, HTTP_SERVICE_UNAVAILABLE, """{"error":"run-create-refused"}""")
        } else if (script.admissionRefusalCode != null) {
            behavior.recordSelection(sessionId, model, effort, accepted = false)
            respond(exchange, HTTP_BAD_REQUEST, JourneyGatewayJson.admissionRefusalJson(script))
        } else {
            behavior.recordSelection(sessionId, model, effort, accepted = true)
            respond(
                exchange,
                HTTP_ACCEPTED,
                """{"run_id":${script.runId.jsonValue()},"status":${script.createStatus.jsonValue()},""" +
                    """"replayed":false}""",
            )
        }
    }

    private fun handleRunResource(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
    ) {
        val resource = exchange.requestURI.path.removePrefix("/v1/runs/")
        val segments = resource.split('/')
        val runId = segments.firstOrNull().orEmpty()
        val runResourceIsUnknown =
            runId.isEmpty() || segments.size !in 1..2 || (segments.size == 2 && segments[1] != "events")
        if (runResourceIsUnknown) {
            respond(exchange, HTTP_NOT_FOUND, """{"error":"run-not-found"}""")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> {
                if (segments.size == 2) {
                    JourneyGatewaySse.streamRunEvents(exchange, behavior, runId)
                } else {
                    val status = behavior.runStatus(runId)
                    if (status == null) {
                        respond(exchange, HTTP_NOT_FOUND, """{"error":"run-not-found"}""")
                    } else {
                        behavior.runStatusRequests.incrementAndGet()
                        respond(
                            exchange,
                            HTTP_OK,
                            """{"object":"hermes.run","run_id":${runId.jsonValue()},"session_id":${behavior.sessionIdOf(
                                runId,
                            ).jsonValue()},"status":${status.jsonValue()},"updated_at":1757325600.0}""",
                        )
                    }
                }
            }
            else -> respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"method-not-allowed"}""")
        }
    }
}

private object JourneyGatewaySse {
    internal fun streamRunEvents(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        runId: String,
    ) {
        val script =
            behavior.scenario.runs.firstOrNull { it.runId == runId } ?: run {
                respond(exchange, HTTP_NOT_FOUND, """{"error":"run-not-found"}""")
                return
            }
        behavior.sseOpened.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(HTTP_OK, 0)
        try {
            writeObservationEvents(exchange, behavior, script, runId)
        } catch (_: Exception) {
            if (script.finalizeOnDisconnect) {
                behavior.observationDelivered[runId] = true
            }
        } finally {
            behavior.sseClosed.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
            exchange.close()
        }
    }

    private fun writeObservationEvents(
        exchange: HttpExchange,
        behavior: JourneyGatewayBehavior,
        script: JourneyRunScript,
        runId: String,
    ) {
        val interruptAfter = script.interruptAfterEvents
        val events = script.observation
        if (script.liveFraming) {
            exchange.responseBody.write(streamOpenComment)
            exchange.responseBody.flush()
        }
        events.forEachIndexed { index, event ->
            if (!script.holdOpen && script.finalStatus != null && index == events.lastIndex) {
                behavior.observationDelivered[runId] = true
            }
            exchange.responseBody.write(JourneyGatewayJson.renderEvent(script, event, index))
            exchange.responseBody.flush()
            if (interruptAfter != null && index + 1 >= interruptAfter) {
                behavior.interruptedRunIds += runId
                exchange.close()
                return
            }
        }
        if (script.holdOpen) {
            writeKeepAliveEvents(exchange)
            if (script.finalizeOnDisconnect) {
                behavior.observationDelivered[runId] = true
            }
        } else if (script.finalStatus != null && events.isEmpty()) {
            behavior.observationDelivered[runId] = true
        }
        if (script.liveFraming) {
            exchange.responseBody.write(streamClosedComment)
            exchange.responseBody.flush()
        }
    }

    private fun writeKeepAliveEvents(exchange: HttpExchange) {
        try {
            while (true) {
                exchange.responseBody.write(keepAliveComment)
                exchange.responseBody.flush()
                Thread.sleep(HOLD_OPEN_KEEPALIVE_MILLIS)
            }
        } catch (_: Exception) {
        }
    }
}

private object JourneyGatewayJson {
    internal fun capabilitiesJson(scenario: JourneyScenario): String =
        buildString {
            append("""{"object":"hermes.api_server.capabilities","platform":"hermes-agent",""")
            append(""""model":"synthetic","auth":{"type":"bearer","required":""")
            append(scenario.requireBearerCredential != null)
            append(
                "},\"features\":{\"run_submission\":true,\"run_status\":true," +
                    "\"run_events_sse\":true,\"session_resources\":true},\"endpoints\":{",
            )
            append(
                scenario.capabilities.joinToString(",") { name ->
                    val endpoint = cataloguedEndpoint(scenario, name)
                    val method = GatewayHttpSupport.quote(endpoint.method)
                    val path = GatewayHttpSupport.quote(endpoint.path)
                    """${GatewayHttpSupport.quote(
                        name,
                    )}:{"method":$method,"path":$path}"""
                },
            )
            append("}}")
        }

    private fun cataloguedEndpoint(
        scenario: JourneyScenario,
        name: String,
    ): JourneyEndpointCatalog.Endpoint =
        if (name == "run_models" && scenario.modelCatalogRow != null) {
            JourneyEndpointCatalog.Endpoint(scenario.modelCatalogRow.method, scenario.modelCatalogRow.path)
        } else {
            JourneyEndpointCatalog.endpoints.getValue(name)
        }

    internal fun catalogJson(scenario: JourneyScenario): String {
        val catalog = requireNotNull(scenario.modelCatalog) { "The scenario declares no model catalog." }
        val models = catalog.entries.joinToString(",") { entry -> catalogEntryJson(entry) }
        return "{\"object\":\"hermes.api_server.run_models\",\"default_model\":" +
            catalog.defaultModel.jsonValue() + ",\"models\":[" + models + "]}"
    }

    private fun catalogEntryJson(entry: JourneyModelCatalogEntry): String {
        val effort =
            if (entry.supportedEfforts.isEmpty()) {
                ""
            } else {
                val efforts = entry.supportedEfforts.joinToString(",") { GatewayHttpSupport.quote(it) }
                ",\"effort\":{\"supported\":[" + efforts + "],\"default\":" +
                    entry.defaultEffort.jsonValue() + "}"
            }
        return "{\"id\":" + entry.id.jsonValue() + ",\"display_name\":" + entry.displayName.jsonValue() +
            ",\"provider\":" + entry.provider.jsonValue() + effort + "}"
    }

    internal fun admissionRefusalJson(script: JourneyRunScript): String {
        val code = requireNotNull(script.admissionRefusalCode).jsonValue()
        return "{\"error\":{\"message\":\"synthetic-admission-refusal\"," +
            "\"type\":\"invalid_request_error\",\"param\":null,\"code\":$code}}"
    }

    internal fun renderEvent(
        script: JourneyRunScript,
        event: JourneyRunEvent,
        eventIndex: Int,
    ): ByteArray {
        val data =
            buildString {
                append("""{"event":${event.type.jsonValue()},"run_id":${script.runId.jsonValue()}""")
                event.delta?.let { append(""","delta":${it.jsonValue()}""") }
                append('}')
            }
        val record =
            buildString {
                if (script.liveFraming) {
                    append("id: $eventIndex\n")
                }
                append("data: $data\n\n")
            }
        return record.toByteArray(Charsets.UTF_8)
    }

    internal fun sessionJson(session: JourneySession): String =
        buildString {
            append(
                """{"id":${session.id.jsonValue()},"source":"api_server","title":${session.title.jsonValue()},""",
            )
            append(""""preview":${session.preview.jsonValue()},"pinned":${session.pinned},""")
            append(""""message_count":${session.history.size}}""")
        }

    internal fun historyJson(
        behavior: JourneyGatewayBehavior,
        session: JourneySession,
    ): String {
        val messages = behavior.historyMessagesFor(session)
        return buildString {
            append("""{"object":"list","session_id":${session.id.jsonValue()},"data":[""")
            append(messages.joinToString(",") { messageJson(it) })
            append("""],"pagination":{"limit":500,"offset":0,"order":"latest","returned":${messages.size}}}""")
        }
    }

    private fun messageJson(message: JourneyMessage): String =
        buildString {
            append(
                """{"id":${message.id.jsonValue()},"role":${message.role.jsonValue()},""" +
                    """"content":${message.content.jsonValue()}""",
            )
            message.timestamp?.let { append(""","timestamp":${it.jsonValue()}""") }
            append('}')
        }
}

private object JourneyGatewayBodyJson {
    internal fun parseTitle(body: String?): String? = parseStringField(body, "title")

    internal fun parseStringField(
        body: String?,
        field: String,
    ): String? {
        val root =
            body?.takeIf(String::isNotBlank)
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
                as? kotlinx.serialization.json.JsonObject
                ?: return null
        val value = root[field]
        return if (value == null || value == kotlinx.serialization.json.JsonNull) {
            null
        } else {
            (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    internal fun parseBooleanField(
        body: String?,
        field: String,
    ): Boolean? {
        val root =
            body?.takeIf(String::isNotBlank)
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
                as? kotlinx.serialization.json.JsonObject
        val value = root?.get(field) as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return if (value.isString || value.content !in setOf("true", "false")) {
            null
        } else {
            value.content == "true"
        }
    }

    internal fun parseRunInput(body: String?): String? {
        val root =
            body?.takeIf(String::isNotBlank)
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
                as? kotlinx.serialization.json.JsonObject
                ?: return null
        return (root["input"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * Blocks every Session list and Session resource request once one list has succeeded.
 * With a [recoversAfterBlockedSessionLists] bound, the gate stops blocking after that
 * many blocked list requests, so a journey can script offline content followed by a
 * recovered authoritative read.
 */
internal class OfflineAfterFirstSessionListGate(
    private val enabled: Boolean,
    private val recoversAfterBlockedSessionLists: Int? = null,
) {
    private val sessionListRequests = AtomicInteger(0)
    private val blockedSessionListRequests = AtomicInteger(0)

    @Volatile
    private var armed = false

    @Volatile
    private var recovered = false

    val blocking: Boolean
        get() = enabled && armed && !recovered

    fun onSessionListRequest(): Boolean {
        if (!enabled || sessionListRequests.incrementAndGet() <= 1) {
            return false
        }
        armed = true
        val blocked = if (recovered) null else blockedSessionListRequests.incrementAndGet()
        val shouldBlock =
            blocked != null &&
                (recoversAfterBlockedSessionLists == null || blocked <= recoversAfterBlockedSessionLists)
        if (!shouldBlock) {
            recovered = true
        }
        return shouldBlock
    }
}

/**
 * Blocks every Session transcript read once one has succeeded, leaving the Session list and
 * the companion Session resource reads untouched. With a [recoversAfterBlockedSessionOpens]
 * bound, the gate stops blocking after that many blocked transcript reads, so a journey can
 * script a cached-transcript arm followed by a recovered authoritative re-open. The recovery
 * arm must stay reachable from the same route the gate blocks, which is why the companion
 * Session read is deliberately not blocked while this gate is armed.
 */
internal class OfflineAfterFirstSessionOpenGate(
    private val enabled: Boolean,
    private val recoversAfterBlockedSessionOpens: Int? = null,
) {
    private val sessionHistoryRequests = AtomicInteger(0)
    private val blockedSessionHistoryRequests = AtomicInteger(0)

    @Volatile
    private var recovered = false

    fun onSessionHistoryRequest(): Boolean {
        if (!enabled || sessionHistoryRequests.incrementAndGet() <= 1) {
            return false
        }
        val blocked = if (recovered) null else blockedSessionHistoryRequests.incrementAndGet()
        val shouldBlock =
            blocked != null &&
                (recoversAfterBlockedSessionOpens == null || blocked <= recoversAfterBlockedSessionOpens)
        if (!shouldBlock) {
            recovered = true
        }
        return shouldBlock
    }
}

internal data class JourneyRunSelectionRecord(
    val sessionId: String,
    val model: String?,
    val effort: String?,
    val accepted: Boolean,
)

internal class JourneyGatewayBehavior(
    val scenario: JourneyScenario,
) {
    val runSelections = CopyOnWriteArrayList<JourneyRunSelectionRecord>()
    val sessions = CopyOnWriteArrayList(scenario.sessions)
    val requests = CopyOnWriteArrayList<SyntheticGatewayRequest>()
    val sessionMutations = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    private var failNextSessionList = scenario.failNextSessionList

    val offlineGate =
        OfflineAfterFirstSessionListGate(
            enabled = scenario.failAfterFirstSessionList,
            recoversAfterBlockedSessionLists = scenario.offlineRecoversAfterBlockedSessionLists,
        )

    val offlineOpenGate =
        OfflineAfterFirstSessionOpenGate(
            enabled = scenario.failAfterFirstSessionOpen,
            recoversAfterBlockedSessionOpens = scenario.offlineRecoversAfterBlockedSessionOpens,
        )

    val sseOpened: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    val sseClosed: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    val runStatusRequests = AtomicInteger(0)
    val interruptedRunIds = CopyOnWriteArrayList<String>()
    private val createdRunCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val createdSessionCounter = AtomicInteger(0)
    internal val observationDelivered = ConcurrentHashMap<String, Boolean>()

    fun recordSelection(
        sessionId: String,
        model: String?,
        effort: String?,
        accepted: Boolean,
    ) {
        runSelections += JourneyRunSelectionRecord(sessionId, model, effort, accepted)
    }

    fun consumeFailNextSessionList(): Boolean {
        if (!failNextSessionList) return false
        failNextSessionList = false
        return true
    }

    fun createdSessionId(): String {
        val counter = createdSessionCounter.incrementAndGet()
        return "synthetic-created-session-$counter"
    }

    fun replaceSession(session: JourneySession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    fun nextRunScript(sessionId: String): JourneyRunScript? {
        val index = createdRunCounters.getOrPut(sessionId, ::AtomicInteger).getAndIncrement()
        return scenario.runs.filter { it.sessionId == sessionId }.getOrNull(index)
    }

    fun runStatus(runId: String): String? {
        val script = scenario.runs.firstOrNull { it.runId == runId } ?: return null
        return if (observationDelivered[runId] == true || runId in interruptedRunIds) {
            script.finalStatus ?: script.createStatus
        } else {
            script.createStatus
        }
    }

    fun sessionIdOf(runId: String): String? = scenario.runs.firstOrNull { it.runId == runId }?.sessionId

    fun historyMessagesFor(session: JourneySession): List<JourneyMessage> {
        val terminalHistory =
            scenario.runs
                .filter { run ->
                    run.sessionId == session.id &&
                        (observationDelivered[run.runId] == true || run.runId in interruptedRunIds)
                }
                .flatMap(JourneyRunScript::terminalHistory)
        return (session.history + terminalHistory).toList()
    }

    fun telemetryJson(): String =
        buildString {
            append("""{"scenario":${scenario.name.jsonValue()},"provenance":${scenario.hermesRevision.jsonValue()}""")
            append(""","requests":[""")
            append(
                requests.joinToString(",") { request ->
                    """{"method":${request.method.jsonValue()},"path":${request.path.jsonValue()}}"""
                },
            )
            append(']')
            append(""","session_mutations":[""")
            append(
                sessionMutations.joinToString(",") { (operation, sessionId) ->
                    """{"operation":${operation.jsonValue()},"session_id":${sessionId.jsonValue()}}"""
                },
            )
            append(']')
            append(""","run_status_requests":${runStatusRequests.get()}""")
            append(""","run_creates":{""")
            append(
                scenario.sessions.joinToString(",") { session ->
                    """${session.id.jsonValue()}:${createdRunCounters[session.id]?.get() ?: 0}"""
                },
            )
            append('}')
            append(""","run_selections":[""")
            append(
                runSelections.joinToString(",") { selection ->
                    "{\"session_id\":${selection.sessionId.jsonValue()}," +
                        "\"model\":${selection.model.jsonValue()}," +
                        "\"effort\":${selection.effort.jsonValue()}," +
                        "\"accepted\":${selection.accepted}}"
                },
            )
            append(']')
            append(""","interrupted_run_ids":[${interruptedRunIds.joinToString(",") { it.jsonValue() }}]""")
            append(""","sse_connections":{""")
            append(
                scenario.runs.joinToString(",") { run ->
                    val opened = sseOpened[run.runId]?.get() ?: 0
                    val closed = sseClosed[run.runId]?.get() ?: 0
                    """${run.runId.jsonValue()}:{"opened":$opened,"closed":$closed}"""
                },
            )
            append('}')
            append('}')
        }

    private fun String.jsonValue(): String = GatewayHttpSupport.quote(this)
}
