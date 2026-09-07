package org.hermesnative.client.fixture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SyntheticGatewayBehavior(
    val healthStatus: Int = 200,
    val capabilityStatus: Int = 200,
    val capabilities: Set<String> = setOf("client-manifest"),
) {
    init {
        require(healthStatus in 100..599) { "healthStatus must be an HTTP status." }
        require(capabilityStatus in 100..599) { "capabilityStatus must be an HTTP status." }
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
    override var isRunning: Boolean = true
        private set

    override fun stop() {
        if (!isRunning) {
            return
        }
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(1, TimeUnit.SECONDS)) {
            "Synthetic Gateway executor did not stop."
        }
        isRunning = false
    }

    companion object {
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
                respond(exchange, behavior.healthStatus, "{\"status\":\"synthetic\"}")
            }
            server.createContext("/v1/capabilities") { exchange ->
                val capabilities = behavior.capabilities.sorted().joinToString(",") { "\"$it\"" }
                respond(
                    exchange,
                    behavior.capabilityStatus,
                    "{\"capabilities\":[$capabilities]}",
                )
            }
            server.createContext("/") { exchange ->
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
