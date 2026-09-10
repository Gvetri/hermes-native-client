package org.hermesnative.client.fixture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SyntheticGatewayMessage(
    val id: String,
    val role: String,
    val content: String,
)

data class SyntheticGatewaySession(
    val id: String,
    val title: String?,
    val preview: String?,
    val pinned: Boolean,
    val updatedAt: String?,
    val history: List<SyntheticGatewayMessage> = emptyList(),
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
    val capabilities: Set<String> = setOf("client-manifest"),
    initialSessions: List<SyntheticGatewaySession> = emptyList(),
) {
    init {
        require(healthStatus in 100..599) { "healthStatus must be an HTTP status." }
        require(capabilityStatus in 100..599) { "capabilityStatus must be an HTTP status." }
    }

    val sessions = CopyOnWriteArrayList(initialSessions)
    val requests = CopyOnWriteArrayList<SyntheticGatewayRequest>()

    @Volatile
    var failNextSessionList: Boolean = false

    @Volatile
    var sessionPageSize: Int? = null

    val createdSessionId: String = "55555555-5555-4555-8555-555555555555"
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
                val capabilities = behavior.capabilities.sorted().joinToString(",") { quote(it) }
                respond(
                    exchange,
                    behavior.capabilityStatus,
                    "{\"capabilities\":[$capabilities]}",
                )
            }
            server.createContext("/v1/sessions") { exchange ->
                handleSessionListRequest(exchange, behavior)
            }
            server.createContext("/v1/sessions/") { exchange ->
                handleSessionResourceRequest(exchange, behavior)
            }
            server.createContext("/") { exchange ->
                recordRequest(exchange, behavior)
                respond(exchange, 404, "{\"error\":\"not-found\"}")
            }
            server.start()
            return LocalSyntheticGatewayProcess(
                server = server,
                executor = executor,
                endpoint = URI.create("http://127.0.0.1:${server.address.port}"),
                provenanceValue = pinnedProvenance.value,
            )
        }

        private fun handleSessionListRequest(
            exchange: HttpExchange,
            behavior: SyntheticGatewayBehavior,
        ) {
            val body = recordRequest(exchange, behavior)
            if (exchange.requestURI.path != "/v1/sessions") {
                respond(exchange, 404, "{\"error\":\"not-found\"}")
                return
            }
            when (exchange.requestMethod) {
                "GET" -> {
                    if (behavior.failNextSessionList) {
                        behavior.failNextSessionList = false
                        respond(exchange, 503, "{\"error\":\"synthetic-refresh-failure\"}")
                        return
                    }
                    val query = queryParameters(exchange.requestURI)
                    val search = query["search"].orEmpty()
                    val matchingSessions =
                        if (search.isBlank()) {
                            behavior.sessions.toList()
                        } else {
                            behavior.sessions.filter { session ->
                                session.title.orEmpty().contains(search, ignoreCase = true) ||
                                    session.preview.orEmpty().contains(search, ignoreCase = true)
                            }
                        }
                    val cursor = query["cursor"]
                    val parsedOffset = cursor?.removePrefix("offset:")?.toIntOrNull()
                    if (cursor != null && !cursor.startsWith("offset:")) {
                        respond(exchange, 400, "{\"error\":\"invalid-cursor\"}")
                        return
                    }
                    if (cursor != null && parsedOffset == null) {
                        respond(exchange, 400, "{\"error\":\"invalid-cursor\"}")
                        return
                    }
                    val offset = parsedOffset ?: 0
                    val pageSize = behavior.sessionPageSize ?: query["limit"]?.toIntOrNull() ?: matchingSessions.size
                    if (pageSize <= 0) {
                        respond(exchange, 400, "{\"error\":\"invalid-limit\"}")
                        return
                    }
                    if (offset !in 0..matchingSessions.size) {
                        respond(exchange, 400, "{\"error\":\"cursor-out-of-range\"}")
                        return
                    }
                    val page = matchingSessions.drop(offset).take(pageSize)
                    val nextOffset = offset + page.size
                    val nextCursor =
                        nextOffset.takeIf { it < matchingSessions.size }?.let { next -> "offset:$next" }
                    val sessions = page.joinToString(",") { sessionJson(it) }
                    respond(
                        exchange,
                        200,
                        "{\"sessions\":[$sessions],\"next_cursor\":${nextCursor.jsonValue()}}",
                    )
                }
                "POST" -> {
                    val created =
                        SyntheticGatewaySession(
                            id = behavior.createdSessionId,
                            title = parseCreateTitle(body),
                            preview = null,
                            pinned = false,
                            updatedAt = "2026-09-09T00:00:00Z",
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
                    respond(exchange, 201, "{\"session\":${sessionJson(created)}}")
                }
                else -> respond(exchange, 405, "{\"error\":\"method-not-allowed\"}")
            }
        }

        private fun handleSessionResourceRequest(
            exchange: HttpExchange,
            behavior: SyntheticGatewayBehavior,
        ) {
            recordRequest(exchange, behavior)
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, "{\"error\":\"method-not-allowed\"}")
                return
            }

            val resource = exchange.requestURI.path.removePrefix("/v1/sessions/")
            val segments = resource.split('/')
            val sessionId = segments.firstOrNull().orEmpty()
            val session = behavior.sessions.firstOrNull { it.id == sessionId }
            if (session == null || segments.size !in 1..2) {
                respond(exchange, 404, "{\"error\":\"session-not-found\"}")
                return
            }

            when {
                segments.size == 1 -> respond(exchange, 200, "{\"session\":${sessionJson(session)}}")
                segments[1] == "history" -> respond(exchange, 200, historyJson(session))
                else -> respond(exchange, 404, "{\"error\":\"not-found\"}")
            }
        }

        private fun sessionJson(session: SyntheticGatewaySession): String =
            buildString {
                append("{\"id\":")
                append(quote(session.id))
                append(",\"title\":")
                append(session.title.jsonValue())
                append(",\"preview\":")
                append(session.preview.jsonValue())
                append(",\"pinned\":")
                append(session.pinned)
                append(",\"updated_at\":")
                append(session.updatedAt.jsonValue())
                append('}')
            }

        private fun historyJson(session: SyntheticGatewaySession): String =
            buildString {
                append("{\"session_id\":")
                append(quote(session.id))
                append(",\"messages\":[")
                append(session.history.joinToString(",") { messageJson(it) })
                append("],\"next_cursor\":null}")
            }

        private fun messageJson(message: SyntheticGatewayMessage): String =
            buildString {
                append("{\"id\":")
                append(quote(message.id))
                append(",\"role\":")
                append(quote(message.role))
                append(",\"content\":")
                append(quote(message.content))
                append('}')
            }

        private fun String?.jsonValue(): String = this?.let(::quote) ?: "null"

        private fun queryParameters(uri: URI): Map<String, String> =
            uri.rawQuery.orEmpty()
                .split('&')
                .filter(String::isNotEmpty)
                .mapNotNull { parameter ->
                    val separator = parameter.indexOf('=')
                    if (separator < 0) {
                        null
                    } else {
                        URLDecoder.decode(parameter.substring(0, separator), Charsets.UTF_8.name()) to
                            URLDecoder.decode(parameter.substring(separator + 1), Charsets.UTF_8.name())
                    }
                }
                .toMap()

        private fun quote(value: String): String = JsonPrimitive(value).toString()

        private fun parseCreateTitle(body: String?): String? {
            val text = body?.takeIf(String::isNotBlank) ?: return null
            val root =
                runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val title = root["title"] ?: return null
            return if (title == JsonNull) null else (title as? JsonPrimitive)?.content
        }

        private fun recordRequest(
            exchange: HttpExchange,
            behavior: SyntheticGatewayBehavior,
        ): String? {
            val body = exchange.requestBody.bufferedReader().use { it.readText().takeIf(String::isNotEmpty) }
            behavior.requests +=
                SyntheticGatewayRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    hasAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization") != null,
                    body = body,
                )
            return body
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }
}
