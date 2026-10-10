package org.hermesnative.client.fixture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val DEFAULT_PAGE_LIMIT = 50
private const val MIN_HTTP_STATUS = 100
private const val MAX_HTTP_STATUS = 599

data class SyntheticGatewayMessage(
    val id: String,
    val role: String?,
    val content: String?,
    val timestamp: String? = null,
)

data class SyntheticGatewaySession(
    val id: String,
    val title: String?,
    val preview: String?,
    val pinned: Boolean,
    val history: List<SyntheticGatewayMessage> = emptyList(),
)

data class SyntheticEndpoint(
    val method: String,
    val path: String,
)

data class SyntheticGatewayRequest(
    val method: String,
    val path: String,
    val query: String?,
    val hasAuthorizationHeader: Boolean,
    val body: String? = null,
)

class SyntheticGatewayBehavior(
    val healthStatus: Int = 200,
    val capabilityStatus: Int = 200,
    val endpoints: Map<String, SyntheticEndpoint> = defaultCapabilityEndpoints(),
    initialSessions: List<SyntheticGatewaySession> = emptyList(),
) {
    init {
        require(healthStatus in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "healthStatus must be an HTTP status." }
        require(capabilityStatus in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "capabilityStatus must be an HTTP status." }
    }

    val sessions = CopyOnWriteArrayList(initialSessions)
    val requests = CopyOnWriteArrayList<SyntheticGatewayRequest>()

    @Volatile
    var failNextSessionList: Boolean = false

    /** Server-side page cap; the response echoes the effective limit like the pinned Gateway. */
    @Volatile
    var sessionPageSize: Int? = null

    @Volatile
    var failNextSessionRename: Boolean = false

    @Volatile
    var failNextSessionDelete: Boolean = false

    @Volatile
    var failNextSessionPin: Boolean = false

    @Volatile
    var failNextSessionUnpin: Boolean = false

    val createdSessionId: String = "55555555-5555-4555-8555-555555555555"

    companion object {
        fun defaultCapabilityEndpoints(): Map<String, SyntheticEndpoint> =
            linkedMapOf(
                "health" to SyntheticEndpoint("GET", "/health"),
                "sessions" to SyntheticEndpoint("GET", "/api/sessions"),
                "session_create" to SyntheticEndpoint("POST", "/api/sessions"),
                "session" to SyntheticEndpoint("GET", "/api/sessions/{session_id}"),
                "session_update" to SyntheticEndpoint("PATCH", "/api/sessions/{session_id}"),
                "session_delete" to SyntheticEndpoint("DELETE", "/api/sessions/{session_id}"),
                "session_messages" to SyntheticEndpoint("GET", "/api/sessions/{session_id}/messages"),
                "runs" to SyntheticEndpoint("POST", "/v1/runs"),
                "run_status" to SyntheticEndpoint("GET", "/v1/runs/{run_id}"),
                "run_events" to SyntheticEndpoint("GET", "/v1/runs/{run_id}/events"),
            )
    }
}

/** A loopback-only HTTP process with deterministic health, capability, and synthetic behavior. */
class LocalSyntheticGatewayProcess private constructor(
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
            "Synthetic Gateway executor did not stop."
        }
        isRunning = false
    }

    companion object {
        fun start(
            descriptor: PinnedFixtureDescriptor,
            behavior: SyntheticGatewayBehavior = SyntheticGatewayBehavior(),
        ): LocalSyntheticGatewayProcess =
            start(
                descriptor = descriptor,
                pinnedProvenance = descriptor.provenance,
                behavior = behavior,
            )

        internal fun start(
            descriptor: PinnedFixtureDescriptor,
            pinnedProvenance: PinnedFixtureProvenance,
            behavior: SyntheticGatewayBehavior = SyntheticGatewayBehavior(),
        ): LocalSyntheticGatewayProcess {
            require(descriptor.name == "hermes-deterministic-gateway") {
                "Unsupported deterministic Gateway descriptor '${descriptor.name}'."
            }
            require(pinnedProvenance == descriptor.provenance) {
                "Synthetic Gateway provenance does not match the pinned descriptor."
            }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val executor = Executors.newSingleThreadExecutor()
            server.executor = executor
            server.createContext("/health") { exchange ->
                recordRequest(exchange, behavior)
                respond(exchange, behavior.healthStatus, "{\"status\":\"synthetic\"}")
            }
            server.createContext("/v1/capabilities") { exchange ->
                recordRequest(exchange, behavior)
                respond(exchange, behavior.capabilityStatus, capabilitiesJson(behavior))
            }
            server.createContext("/api/sessions") { exchange ->
                SyntheticGatewayRoutes.handleSessionListRequest(exchange, behavior)
            }
            server.createContext("/api/sessions/") { exchange ->
                SyntheticGatewayRoutes.handleSessionResourceRequest(exchange, behavior)
            }
            server.createContext("/") { exchange ->
                recordRequest(exchange, behavior)
                respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"not-found\"}")
            }
            server.start()
            return LocalSyntheticGatewayProcess(
                server = server,
                executor = executor,
                endpoint = URI.create("http://127.0.0.1:${server.address.port}"),
                provenanceValue = pinnedProvenance.value,
            )
        }

        private fun capabilitiesJson(behavior: SyntheticGatewayBehavior): String =
            buildString {
                append("{\"object\":\"hermes.api_server.capabilities\",\"platform\":\"hermes-agent\",")
                append("\"model\":\"synthetic\",")
                append("\"auth\":{\"type\":\"bearer\",\"required\":false},")
                append("\"features\":{\"run_submission\":true,\"run_status\":true,\"run_events_sse\":true,")
                append("\"session_resources\":true},")
                append("\"endpoints\":{")
                append(
                    behavior.endpoints.entries.joinToString(",") { (name, endpoint) ->
                        "${quote(name)}:{\"method\":${quote(endpoint.method)},\"path\":${quote(endpoint.path)}}"
                    },
                )
                append("}}")
            }
    }
}

private fun replaceSession(
    behavior: SyntheticGatewayBehavior,
    session: SyntheticGatewaySession,
) {
    val index = behavior.sessions.indexOfFirst { it.id == session.id }
    if (index >= 0) behavior.sessions[index] = session
}

private fun queryParameters(uri: URI): Map<String, String> = GatewayHttpSupport.queryParameters(uri)

private fun quote(value: String): String = GatewayHttpSupport.quote(value)

private fun recordRequest(
    exchange: HttpExchange,
    behavior: SyntheticGatewayBehavior,
): String? = GatewayHttpSupport.recordRequest(exchange, behavior.requests)

private fun respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    GatewayHttpSupport.respond(exchange, status, body)
}

private object SyntheticGatewayRoutes {
    internal fun handleSessionListRequest(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
    ) {
        val body = recordRequest(exchange, behavior)
        if (exchange.requestURI.path != "/api/sessions") {
            respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"not-found\"}")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> handleSessionListRead(exchange, behavior)
            "POST" -> {
                val created =
                    SyntheticGatewaySession(
                        id = behavior.createdSessionId,
                        title = SyntheticGatewayJson.parseTitle(body),
                        preview = null,
                        pinned = false,
                        history =
                            listOf(
                                SyntheticGatewayMessage(
                                    id = "message-${behavior.createdSessionId}",
                                    role = "assistant",
                                    content = "Created history",
                                ),
                            ),
                    )
                behavior.sessions.removeIf { it.id == created.id }
                behavior.sessions += created
                respond(
                    exchange,
                    HTTP_CREATED,
                    "{\"object\":\"hermes.session\",\"session\":${SyntheticGatewayJson.sessionJson(created)}}",
                )
            }
            else -> respond(exchange, HTTP_METHOD_NOT_ALLOWED, "{\"error\":\"method-not-allowed\"}")
        }
    }

    private fun handleSessionListRead(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
    ) {
        if (behavior.failNextSessionList) {
            behavior.failNextSessionList = false
            respond(exchange, HTTP_SERVICE_UNAVAILABLE, "{\"error\":\"synthetic-refresh-failure\"}")
            return
        }
        val query = queryParameters(exchange.requestURI)
        val requestedLimit = query["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
        val offset = query["offset"]?.toIntOrNull() ?: 0
        val paginationFailure = sessionListPaginationFailure(requestedLimit, offset, behavior)
        if (paginationFailure != null) {
            respond(exchange, HTTP_BAD_REQUEST, paginationFailure)
            return
        }
        val effectiveLimit = minOf(requestedLimit, behavior.sessionPageSize ?: requestedLimit)
        val matchingSessions = behavior.sessions.toList()
        val page = matchingSessions.drop(offset).take(effectiveLimit)
        val hasMore = offset + page.size < matchingSessions.size
        val sessions = page.joinToString(",") { SyntheticGatewayJson.sessionJson(it) }
        respond(
            exchange,
            HTTP_OK,
            "{\"object\":\"list\",\"data\":[$sessions],\"limit\":$effectiveLimit,\"offset\":$offset," +
                "\"has_more\":$hasMore}",
        )
    }

    private fun sessionListPaginationFailure(
        requestedLimit: Int,
        offset: Int,
        behavior: SyntheticGatewayBehavior,
    ): String? =
        when {
            requestedLimit <= 0 || offset < 0 -> "{\"error\":\"invalid-pagination\"}"
            offset > behavior.sessions.size -> "{\"error\":\"offset-out-of-range\"}"
            else -> null
        }

    internal fun handleSessionResourceRequest(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
    ) {
        val body = recordRequest(exchange, behavior)
        val resource = exchange.requestURI.path.removePrefix("/api/sessions/")
        val segments = resource.split('/')
        val sessionId = segments.firstOrNull().orEmpty()
        val session = behavior.sessions.firstOrNull { it.id == sessionId }
        if (session == null || segments.size !in 1..2) {
            respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"session-not-found\"}")
            return
        }

        when (exchange.requestMethod) {
            "GET" ->
                when {
                    segments.size == 1 ->
                        respond(
                            exchange,
                            HTTP_OK,
                            "{\"object\":\"hermes.session\",\"session\":${SyntheticGatewayJson.sessionJson(session)}}",
                        )
                    segments[1] == "messages" -> respond(exchange, HTTP_OK, SyntheticGatewayJson.historyJson(session))
                    else -> respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"not-found\"}")
                }
            "PATCH" -> handleSessionPatch(exchange, behavior, session, body, segments)
            "DELETE" -> handleSessionDelete(exchange, behavior, sessionId, segments)
            else -> respond(exchange, HTTP_METHOD_NOT_ALLOWED, "{\"error\":\"method-not-allowed\"}")
        }
    }

    private fun handleSessionPatch(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
        session: SyntheticGatewaySession,
        body: String?,
        segments: List<String>,
    ) {
        if (segments.size != 1) {
            respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"not-found\"}")
        } else if (SyntheticGatewayJson.bodyContainsField(body, "pinned")) {
            applyPinnedPatch(exchange, behavior, session, body)
        } else {
            applyRenamePatch(exchange, behavior, session, body)
        }
    }

    private fun applyPinnedPatch(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
        session: SyntheticGatewaySession,
        body: String?,
    ) {
        val pinned = SyntheticGatewayJson.bodyBoolean(body, "pinned")
        when {
            pinned == null ->
                respond(exchange, HTTP_BAD_REQUEST, "{\"error\":\"invalid-session-field\"}")
            pinned && behavior.failNextSessionPin -> {
                behavior.failNextSessionPin = false
                respond(exchange, HTTP_SERVICE_UNAVAILABLE, "{\"error\":\"synthetic-pin-failure\"}")
            }
            !pinned && behavior.failNextSessionUnpin -> {
                behavior.failNextSessionUnpin = false
                respond(
                    exchange,
                    HTTP_SERVICE_UNAVAILABLE,
                    "{\"error\":\"synthetic-unpin-failure\"}",
                )
            }
            else -> {
                val updated = session.copy(pinned = pinned)
                replaceSession(behavior, updated)
                respond(
                    exchange,
                    HTTP_OK,
                    "{\"object\":\"hermes.session\",\"session\":${SyntheticGatewayJson.sessionJson(updated)}}",
                )
            }
        }
    }

    private fun applyRenamePatch(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
        session: SyntheticGatewaySession,
        body: String?,
    ) {
        if (behavior.failNextSessionRename) {
            behavior.failNextSessionRename = false
            respond(
                exchange,
                HTTP_SERVICE_UNAVAILABLE,
                "{\"error\":\"synthetic-rename-failure\"}",
            )
        } else {
            val renamed = session.copy(title = SyntheticGatewayJson.parseTitle(body))
            replaceSession(behavior, renamed)
            respond(
                exchange,
                HTTP_OK,
                "{\"object\":\"hermes.session\",\"session\":${SyntheticGatewayJson.sessionJson(renamed)}}",
            )
        }
    }

    private fun handleSessionDelete(
        exchange: HttpExchange,
        behavior: SyntheticGatewayBehavior,
        sessionId: String,
        segments: List<String>,
    ) {
        when {
            segments.size != 1 -> respond(exchange, HTTP_NOT_FOUND, "{\"error\":\"not-found\"}")
            behavior.failNextSessionDelete -> {
                behavior.failNextSessionDelete = false
                respond(exchange, HTTP_SERVICE_UNAVAILABLE, "{\"error\":\"synthetic-delete-failure\"}")
            }
            else -> {
                behavior.sessions.removeIf { it.id == sessionId }
                respond(
                    exchange,
                    HTTP_OK,
                    "{\"object\":\"hermes.session.deleted\",\"id\":${quote(sessionId)},\"deleted\":true}",
                )
            }
        }
    }
}

private object SyntheticGatewayJson {
    internal fun sessionJson(session: SyntheticGatewaySession): String =
        buildString {
            append("{\"id\":")
            append(quote(session.id))
            append(",\"source\":\"api_server\",\"title\":")
            append(session.title.jsonValue())
            append(",\"preview\":")
            append(session.preview.jsonValue())
            append(",\"pinned\":")
            append(session.pinned)
            append(",\"message_count\":")
            append(session.history.size)
            append('}')
        }

    internal fun historyJson(session: SyntheticGatewaySession): String =
        buildString {
            append("{\"object\":\"list\",\"session_id\":")
            append(quote(session.id))
            append(",\"data\":[")
            append(session.history.joinToString(",") { messageJson(it) })
            append("],\"pagination\":{\"limit\":500,\"offset\":0,\"order\":\"latest\",\"returned\":")
            append(session.history.size)
            append("}}")
        }

    private fun messageJson(message: SyntheticGatewayMessage): String =
        buildString {
            append("{\"id\":")
            append(quote(message.id))
            append(",\"role\":")
            append(message.role.jsonValue())
            append(",\"content\":")
            append(message.content.jsonValue())
            message.timestamp?.let { append(",\"timestamp\":${quote(it)}") }
            append('}')
        }

    internal fun bodyContainsField(
        body: String?,
        field: String,
    ): Boolean {
        val text = body?.takeIf(String::isNotBlank) ?: return false
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return root?.containsKey(field) ?: false
    }

    internal fun bodyBoolean(
        body: String?,
        field: String,
    ): Boolean? {
        val root =
            body?.takeIf(String::isNotBlank)
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        val value = root?.get(field) as? JsonPrimitive ?: return null
        return if (value.isString || value.content !in setOf("true", "false")) {
            null
        } else {
            value.content == "true"
        }
    }

    private fun String?.jsonValue(): String = GatewayHttpSupport.jsonValue(this)

    internal fun parseTitle(body: String?): String? {
        val root =
            body?.takeIf(String::isNotBlank)
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
                ?: return null
        val title = root["title"]
        return if (title == null || title == JsonNull) null else (title as? JsonPrimitive)?.content
    }
}
