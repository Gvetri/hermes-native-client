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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * The controlled fake Gateway for deterministic emulator journeys.
 *
 * Starts from an explicit repository-owned [JourneyScenario], serves synthetic
 * session and Run data over HTTP or TLS, exposes deterministic SSE observation,
 * records request/observation telemetry, and verifies clean teardown. It never
 * contacts a public endpoint and uses no real provider credentials.
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
                val keyStore = loadKeyStore(requireNotNull(keystoreFile) { "Journey TLS scenarios require a keystore." }, keystorePassword)
                https.httpsConfigurator = HttpsConfigurator(serverSslContext(keyStore, keystorePassword))
                https.executor = executor
                server = https
            } else {
                val http = HttpServer.create(InetSocketAddress("127.0.0.1", scenario.port), 0)
                http.executor = executor
                server = http
            }
            val behavior = JourneyGatewayBehavior(scenario)
            server.createContext("/health") { exchange ->
                record(exchange, behavior)
                respond(exchange, 200, """{"status":"synthetic"}""")
            }
            server.createContext("/__fixture/telemetry") { exchange ->
                record(exchange, behavior)
                respond(exchange, 200, behavior.telemetryJson())
            }
            server.createContext("/v1/capabilities") { exchange ->
                record(exchange, behavior)
                if (unauthorized(exchange, behavior)) {
                    respond(exchange, 401, """{"error":"unauthorized"}""")
                } else {
                    val capabilities = behavior.scenario.capabilities.sorted().joinToString(",") { GatewayHttpSupport.quote(it) }
                    respond(exchange, 200, """{"capabilities":[$capabilities]}""")
                }
            }
            server.createContext("/v1/sessions") { exchange ->
                handleSessionList(exchange, behavior)
            }
            server.createContext("/v1/sessions/") { exchange ->
                handleSessionResource(exchange, behavior)
            }
            server.createContext("/v1/runs/") { exchange ->
                handleRunResource(exchange, behavior)
            }
            server.createContext("/") { exchange ->
                record(exchange, behavior)
                respond(exchange, 404, """{"error":"not-found"}""")
            }
            server.start()
            val scheme = if (scenario.tls) "https" else "http"
            return JourneyGatewayProcess(
                server = server,
                executor = executor,
                endpoint = URI.create("$scheme://127.0.0.1:${server.address.port}"),
                provenanceValue = scenario.hermesRevision,
            )
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

        private fun handleSessionList(
            exchange: HttpExchange,
            behavior: JourneyGatewayBehavior,
        ) {
            val body = record(exchange, behavior)
            if (unauthorized(exchange, behavior)) {
                respond(exchange, 401, """{"error":"unauthorized"}""")
                return
            }
            when (exchange.requestMethod) {
                "GET" -> {
                    if (behavior.consumeFailNextSessionList()) {
                        respond(exchange, 503, """{"error":"synthetic-refresh-failure"}""")
                        return
                    }
                    val query = GatewayHttpSupport.queryParameters(exchange.requestURI)
                    val search = query["search"].orEmpty()
                    val matchingSessions =
                        behavior.sessions.filter { session ->
                            search.isBlank() ||
                                session.title.orEmpty().contains(search, ignoreCase = true) ||
                                session.preview.orEmpty().contains(search, ignoreCase = true)
                        }
                    val cursor = query["cursor"]
                    val parsedOffset = cursor?.removePrefix("offset:")?.toIntOrNull()
                    if (cursor != null && !cursor.startsWith("offset:")) {
                        respond(exchange, 400, """{"error":"invalid-cursor"}""")
                        return
                    }
                    if (cursor != null && parsedOffset == null) {
                        respond(exchange, 400, """{"error":"invalid-cursor"}""")
                        return
                    }
                    val offset = parsedOffset ?: 0
                    val pageSize = behavior.scenario.sessionPageSize ?: matchingSessions.size.coerceAtLeast(1)
                    if (pageSize <= 0 || offset !in 0..matchingSessions.size) {
                        respond(exchange, 400, """{"error":"invalid-pagination"}""")
                        return
                    }
                    val page = matchingSessions.drop(offset).take(pageSize)
                    val nextOffset = offset + page.size
                    val nextCursor = nextOffset.takeIf { it < matchingSessions.size }?.let { next -> "offset:$next" }
                    val sessionsJson = page.joinToString(",") { sessionJson(it) }
                    respond(
                        exchange,
                        200,
                        """{"sessions":[$sessionsJson],"next_cursor":${nextCursor.jsonValue()}}""",
                    )
                }
                "POST" -> {
                    val created =
                        JourneySession(
                            id = behavior.createdSessionId(),
                            title = parseTitle(body),
                            preview = null,
                            pinned = false,
                            history =
                                listOf(
                                    JourneyMessage(
                                        id = "message-${behavior.createdSessionId()}",
                                        role = "assistant",
                                        content = "Created history",
                                    ),
                                ),
                        )
                    behavior.sessions.removeIf { it.id == created.id }
                    behavior.sessions += created
                    respond(exchange, 201, """{"session":${sessionJson(created)}}""")
                }
                else -> respond(exchange, 405, """{"error":"method-not-allowed"}""")
            }
        }

        private fun handleSessionResource(
            exchange: HttpExchange,
            behavior: JourneyGatewayBehavior,
        ) {
            val body = record(exchange, behavior)
            if (unauthorized(exchange, behavior)) {
                respond(exchange, 401, """{"error":"unauthorized"}""")
                return
            }
            val resource = exchange.requestURI.path.removePrefix("/v1/sessions/")
            val segments = resource.split('/')
            val sessionId = segments.firstOrNull().orEmpty()
            if (segments.size == 2 && segments[1] == "runs" && exchange.requestMethod == "POST") {
                handleRunCreate(exchange, behavior, sessionId, body)
                return
            }
            val session = behavior.sessions.firstOrNull { it.id == sessionId }
            if (session == null || segments.size !in 1..2) {
                respond(exchange, 404, """{"error":"session-not-found"}""")
                return
            }
            when (exchange.requestMethod) {
                "GET" ->
                    when {
                        segments.size == 1 -> respond(exchange, 200, """{"session":${sessionJson(session)}}""")
                        segments[1] == "history" -> respond(exchange, 200, historyJson(behavior, session))
                        else -> respond(exchange, 404, """{"error":"not-found"}""")
                    }
                "PATCH" -> {
                    if (segments.size != 1) {
                        respond(exchange, 404, """{"error":"not-found"}""")
                    } else {
                        val renamed = session.copy(title = parseTitle(body))
                        behavior.replaceSession(renamed)
                        respond(exchange, 200, """{"session":${sessionJson(renamed)}}""")
                    }
                }
                "POST" -> {
                    if (segments.size != 2 || segments[1] != "pin") {
                        respond(exchange, 404, """{"error":"not-found"}""")
                    } else {
                        val pinned = session.copy(pinned = true)
                        behavior.replaceSession(pinned)
                        respond(exchange, 200, pinJson(pinned))
                    }
                }
                "DELETE" ->
                    when {
                        segments.size == 2 && segments[1] == "pin" -> {
                            val unpinned = session.copy(pinned = false)
                            behavior.replaceSession(unpinned)
                            respond(exchange, 200, pinJson(unpinned))
                        }
                        segments.size == 1 -> {
                            behavior.sessions.removeIf { it.id == sessionId }
                            respond(exchange, 204, "")
                        }
                        else -> respond(exchange, 404, """{"error":"not-found"}""")
                    }
                else -> respond(exchange, 405, """{"error":"method-not-allowed"}""")
            }
        }

        private fun handleRunCreate(
            exchange: HttpExchange,
            behavior: JourneyGatewayBehavior,
            sessionId: String,
            body: String?,
        ) {
            val script =
                behavior.nextRunScript(sessionId)
                    ?: run {
                        respond(exchange, 404, """{"error":"no-synthetic-run"}""")
                        return
                    }
            val runId = script.runId
            respond(
                exchange,
                202,
                """{"run_id":${runId.jsonValue()},"session_id":${sessionId.jsonValue()},"status":${script.createStatus.jsonValue()}}""",
            )
        }

        private fun handleRunResource(
            exchange: HttpExchange,
            behavior: JourneyGatewayBehavior,
        ) {
            val body = record(exchange, behavior)
            if (unauthorized(exchange, behavior)) {
                respond(exchange, 401, """{"error":"unauthorized"}""")
                return
            }
            val resource = exchange.requestURI.path.removePrefix("/v1/runs/")
            val segments = resource.split('/')
            val runId = segments.firstOrNull().orEmpty()
            if (runId.isEmpty() || segments.size !in 1..2 || (segments.size == 2 && segments[1] != "events")) {
                respond(exchange, 404, """{"error":"run-not-found"}""")
                return
            }
            when (exchange.requestMethod) {
                "GET" -> {
                    if (segments.size == 2) {
                        streamRunEvents(exchange, behavior, runId)
                    } else {
                        val status = behavior.runStatus(runId)
                        if (status == null) {
                            respond(exchange, 404, """{"error":"run-not-found"}""")
                        } else {
                            behavior.runStatusRequests.incrementAndGet()
                            respond(
                                exchange,
                                200,
                                """{"run_id":${runId.jsonValue()},"session_id":${behavior.sessionIdOf(
                                    runId,
                                ).jsonValue()},"status":${status.jsonValue()}}""",
                            )
                        }
                    }
                }
                else -> respond(exchange, 405, """{"error":"method-not-allowed"}""")
            }
        }

        private fun streamRunEvents(
            exchange: HttpExchange,
            behavior: JourneyGatewayBehavior,
            runId: String,
        ) {
            val script =
                behavior.scenario.runs.firstOrNull { it.runId == runId } ?: run {
                    respond(exchange, 404, """{"error":"run-not-found"}""")
                    return
                }
            behavior.sseOpened.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            try {
                val interruptAfter = script.interruptAfterEvents
                val events = script.observation
                events.forEachIndexed { index, event ->
                    exchange.responseBody.write(renderEvent(script, event))
                    exchange.responseBody.flush()
                    if (interruptAfter != null && index + 1 >= interruptAfter) {
                        behavior.interruptedRunIds += runId
                        // Abrupt close without a terminal event.
                        exchange.close()
                        return
                    }
                }
                if (script.holdOpen) {
                    // Keep the stream open with SSE comments until the client
                    // disconnects. A client-side close never cancels the Run.
                    try {
                        while (true) {
                            exchange.responseBody.write(keepAliveComment)
                            exchange.responseBody.flush()
                            Thread.sleep(HOLD_OPEN_KEEPALIVE_MILLIS)
                        }
                    } catch (_: Exception) {
                        // The client closed the observation.
                    }
                } else if (script.finalStatus != null) {
                    behavior.observationDelivered[runId] = true
                }
            } catch (_: Exception) {
                // The client closed the observation; a closed stream never
                // cancels the remote Run.
            } finally {
                behavior.sseClosed.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
                exchange.close()
            }
        }

        private fun renderEvent(
            script: JourneyRunScript,
            event: JourneyRunEvent,
        ): ByteArray {
            val data =
                buildString {
                    append("""{"run_id":${script.runId.jsonValue()}""")
                    event.status?.let { append(""","status":${it.jsonValue()}""") }
                    event.delta?.let { append(""","delta":${it.jsonValue()}""") }
                    append('}')
                }
            val rendered =
                buildString {
                    event.id?.let { append("id: ").append(it).append('\n') }
                    append("event: ").append(event.type).append('\n')
                    append("data: ").append(data).append('\n')
                    append('\n')
                }
            return rendered.toByteArray(Charsets.UTF_8)
        }

        private fun sessionJson(session: JourneySession): String =
            buildString {
                append(
                    """{"id":${session.id.jsonValue()},"title":${session.title.jsonValue()},""",
                )
                append(""""preview":${session.preview.jsonValue()},"pinned":${session.pinned},"updated_at":null""")
                append('}')
            }

        private fun historyJson(
            behavior: JourneyGatewayBehavior,
            session: JourneySession,
        ): String {
            val messages = behavior.historyMessagesFor(session)
            return buildString {
                append("""{"session_id":${session.id.jsonValue()},"messages":[""")
                append(messages.joinToString(",") { messageJson(it) })
                append("""],"next_cursor":null}""")
            }
        }

        private fun messageJson(message: JourneyMessage): String =
            buildString {
                append("""{"id":${message.id.jsonValue()},"role":${message.role.jsonValue()},"content":${message.content.jsonValue()}""")
                message.runId?.let { append(""","run_id":${it.jsonValue()}""") }
                message.runStatus?.let { append(""","run_status":${it.jsonValue()}""") }
                message.runResult?.let { append(""","run_result":${it.jsonValue()}""") }
                message.timestamp?.let { append(""","timestamp":${it.jsonValue()}""") }
                append('}')
            }

        private fun pinJson(session: JourneySession): String = """{"session_id":${session.id.jsonValue()},"pinned":${session.pinned}}"""

        private fun parseTitle(body: String?): String? {
            val text = body?.takeIf(String::isNotBlank) ?: return null
            val root =
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
                    as? kotlinx.serialization.json.JsonObject
                    ?: return null
            val title = root["title"] ?: return null
            return if (title == kotlinx.serialization.json.JsonNull) {
                null
            } else {
                (title as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }

        private fun String?.jsonValue(): String = GatewayHttpSupport.jsonValue(this)

        private val keepAliveComment: ByteArray = ": keep-alive\n\n".toByteArray(Charsets.UTF_8)

        private const val DEFAULT_KEYSTORE_PASSWORD = "journey-fixture"
        private const val HOLD_OPEN_KEEPALIVE_MILLIS = 500L
    }
}

internal class JourneyGatewayBehavior(
    val scenario: JourneyScenario,
) {
    val sessions = CopyOnWriteArrayList(scenario.sessions)
    val requests = CopyOnWriteArrayList<SyntheticGatewayRequest>()

    @Volatile
    private var failNextSessionList = scenario.failNextSessionList

    val sseOpened: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    val sseClosed: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    val runStatusRequests = AtomicInteger(0)
    val interruptedRunIds = CopyOnWriteArrayList<String>()
    private val createdRunCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val servedRunIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val createdSessionCounter = AtomicInteger(0)
    internal val observationDelivered = ConcurrentHashMap<String, Boolean>()

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
        val scripts = scenario.runs.filter { it.sessionId == sessionId }
        val script = scripts.getOrNull(index) ?: scripts.lastOrNull()
        if (script != null) {
            servedRunIds.getOrPut(sessionId) { CopyOnWriteArraySet() }.add(script.runId)
        }
        return script
    }

    fun runStatus(runId: String): String? {
        val script = scenario.runs.firstOrNull { it.runId == runId } ?: return null
        if (observationDelivered[runId] == true || runId in interruptedRunIds) {
            return script.finalStatus ?: script.createStatus
        }
        return script.createStatus
    }

    fun sessionIdOf(runId: String): String? = scenario.runs.firstOrNull { it.runId == runId }?.sessionId

    fun historyMessagesFor(session: JourneySession): List<JourneyMessage> {
        val terminalHistory =
            scenario.runs
                .filter {
                        run ->
                    run.sessionId == session.id && (observationDelivered[run.runId] == true || run.runId in interruptedRunIds)
                }
                .flatMap(JourneyRunScript::terminalHistory)
        return (session.history + terminalHistory).toList()
    }

    fun telemetryJson(): String =
        buildString {
            append("""{"scenario":${scenario.name.jsonValue()},"provenance":${scenario.hermesRevision.jsonValue()}""")
            append(""","requests":[""")
            append(
                requests.joinToString(",") { request -> """{"method":${request.method.jsonValue()},"path":${request.path.jsonValue()}}""" },
            )
            append(']')
            append(""","run_status_requests":${runStatusRequests.get()}""")
            append(""","run_creates":{""")
            append(
                scenario.sessions.joinToString(",") { session ->
                    """${session.id.jsonValue()}:${servedRunIds[session.id]?.size ?: 0}"""
                },
            )
            append('}')
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
