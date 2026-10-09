package org.hermesnative.client.fixture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Lifecycle stages exposed to deterministic integration tests. */
enum class FixtureLifecycleState {
    NEW,
    STARTED,
    READY,
    TESTING,
    TORN_DOWN,
}

/** A process boundary for a local deterministic Gateway implementation. */
interface GatewayProcess {
    val endpoint: URI
    val provenanceValue: String
    val isRunning: Boolean

    fun stop()
}

fun interface GatewayProcessFactory {
    fun start(descriptor: PinnedFixtureDescriptor): GatewayProcess
}

fun interface FixtureReadinessChecker {
    fun verify(
        process: GatewayProcess,
        descriptor: PinnedFixtureDescriptor,
    )
}

class FixtureStartupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class FixtureReadinessException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class FixtureCleanupException(
    message: String,
    causes: List<Throwable>,
) : IllegalStateException(message, causes.firstOrNull()) {
    init {
        causes.drop(1).forEach(::addSuppressed)
    }
}

/** Mutable synthetic state that is scoped to one fixture test and never leaves the process. */
class SyntheticTestState internal constructor(
    private val resetFunction: (MutableMap<String, String>) -> Unit = { it.clear() },
) {
    private val values = linkedMapOf<String, String>()

    fun put(
        key: String,
        value: String,
    ) {
        require(key.matches(Regex("[a-z][a-z0-9-]*"))) {
            "Synthetic state keys must use lowercase kebab-case."
        }
        values[key] = value
    }

    fun get(key: String): String? = values[key]

    fun snapshot(): Map<String, String> = values.toMap()

    internal fun reset() = resetFunction(values)
}

data class FixtureTestContext(
    val endpoint: URI,
    val provenanceValue: String,
    val syntheticState: SyntheticTestState,
)

/** Runs one repository-owned deterministic Gateway fixture lifecycle. */
class DeterministicGatewayFixture(
    private val descriptorFile: File,
    private val processFactory: GatewayProcessFactory =
        GatewayProcessFactory { descriptor -> PinnedHermesFixtureLauncher.start(descriptor) },
    private val readinessChecker: FixtureReadinessChecker = HttpFixtureReadinessChecker,
    private val syntheticState: SyntheticTestState = SyntheticTestState(),
) {
    private var descriptor: PinnedFixtureDescriptor? = null
    private var process: GatewayProcess? = null

    var lifecycleState: FixtureLifecycleState = FixtureLifecycleState.NEW
        private set

    val endpoint: URI
        get() = requireProcess().endpoint

    val lastSyntheticState: Map<String, String>
        get() = syntheticState.snapshot()

    fun setup() {
        check(lifecycleState == FixtureLifecycleState.NEW) {
            "Fixture setup is only valid for a new fixture."
        }
        val loadedDescriptor = PinnedFixtureDescriptor.load(descriptorFile)
        descriptor = loadedDescriptor
        try {
            val startedProcess = processFactory.start(loadedDescriptor)
            process = startedProcess
            check(startedProcess.isRunning) { "Deterministic Gateway process did not start." }
            check(startedProcess.provenanceValue == loadedDescriptor.provenanceValue) {
                "Deterministic Gateway process did not use the pinned fixture provenance."
            }
            requireLocalEndpoint(startedProcess.endpoint)
            lifecycleState = FixtureLifecycleState.STARTED
        } catch (error: FixtureStartupException) {
            failStartup(error)
        } catch (error: Throwable) {
            failStartup(FixtureStartupException("Deterministic Gateway fixture startup failed.", error))
        }
    }

    private fun failStartup(startupFailure: FixtureStartupException): Nothing {
        val startedProcess = process
        if (startedProcess != null) {
            var stopFailed = false
            try {
                startedProcess.stop()
            } catch (stopError: Throwable) {
                stopFailed = true
                startupFailure.addSuppressed(stopError)
            }
            if (!stopFailed && !startedProcess.isRunning) {
                process = null
            } else if (startedProcess.isRunning) {
                startupFailure.addSuppressed(
                    IllegalStateException("Deterministic Gateway process did not stop after startup failure."),
                )
            }
        }
        throw startupFailure
    }

    fun awaitReady() {
        check(lifecycleState == FixtureLifecycleState.STARTED) {
            "Fixture readiness requires a started fixture."
        }
        try {
            readinessChecker.verify(requireProcess(), requireNotNull(descriptor))
            lifecycleState = FixtureLifecycleState.READY
        } catch (error: FixtureReadinessException) {
            throw error
        } catch (error: Throwable) {
            throw FixtureReadinessException(
                "Deterministic Gateway fixture readiness failed: ${error.message}",
                error,
            )
        }
    }

    fun <T> runTest(block: (FixtureTestContext) -> T): T {
        check(lifecycleState == FixtureLifecycleState.READY) {
            "Fixture test execution requires an explicitly ready fixture."
        }
        resetAndVerifySyntheticState()
        lifecycleState = FixtureLifecycleState.TESTING
        val executed =
            runCatching {
                val activeProcess = requireProcess()
                block(FixtureTestContext(activeProcess.endpoint, activeProcess.provenanceValue, syntheticState))
            }
        val cleanupFailure = resetSyntheticStateAfterTest()
        lifecycleState = FixtureLifecycleState.READY
        return executed
            .onSuccess { result ->
                if (cleanupFailure != null) throw cleanupFailure
                result
            }
            .getOrElse { error ->
                cleanupFailure?.let(error::addSuppressed)
                throw error
            }
    }

    private fun resetSyntheticStateAfterTest(): FixtureCleanupException? =
        try {
            resetAndVerifySyntheticState()
            null
        } catch (error: Throwable) {
            FixtureCleanupException(
                "Synthetic state reset after the fixture test failed.",
                listOf(error),
            )
        }

    fun <T> execute(block: (FixtureTestContext) -> T): T {
        val executed =
            runCatching {
                setup()
                awaitReady()
                runTest(block)
            }
        return executed.fold(
            onSuccess = { result ->
                teardown()
                result
            },
            onFailure = { error ->
                try {
                    teardown()
                } catch (cleanupFailure: FixtureCleanupException) {
                    error.addSuppressed(cleanupFailure)
                }
                throw error
            },
        )
    }

    fun teardown() {
        if (lifecycleState == FixtureLifecycleState.TORN_DOWN && process == null && syntheticState.snapshot().isEmpty()) {
            return
        }

        val cleanupFailures = mutableListOf<Throwable>()
        try {
            resetAndVerifySyntheticState()
        } catch (error: Throwable) {
            cleanupFailures += error
        }

        val activeProcess = process
        if (activeProcess != null) {
            var stopFailed = false
            try {
                activeProcess.stop()
            } catch (error: Throwable) {
                stopFailed = true
                cleanupFailures += error
            }
            if (activeProcess.isRunning) {
                cleanupFailures += IllegalStateException("Deterministic Gateway process did not stop.")
            }
            if (!stopFailed && !activeProcess.isRunning) {
                process = null
            }
        }
        lifecycleState = FixtureLifecycleState.TORN_DOWN

        if (cleanupFailures.isNotEmpty()) {
            throw FixtureCleanupException("Deterministic Gateway fixture cleanup failed.", cleanupFailures)
        }
    }

    private fun requireProcess(): GatewayProcess = requireNotNull(process) { "Deterministic Gateway fixture is not started." }

    private fun resetAndVerifySyntheticState() {
        syntheticState.reset()
        check(syntheticState.snapshot().isEmpty()) {
            "Deterministic Gateway synthetic state did not reset."
        }
    }

    private fun requireLocalEndpoint(endpoint: URI) {
        require(endpoint.scheme == "http") { "Deterministic Gateway fixture must use HTTP on loopback." }
        require(endpoint.host in setOf("127.0.0.1", "localhost", "::1")) {
            "Deterministic Gateway fixture must bind to loopback, not ${endpoint.host}."
        }
    }

    companion object {
        fun fromDescriptor(descriptorFile: File): DeterministicGatewayFixture = DeterministicGatewayFixture(descriptorFile)
    }
}

private const val CLIENT_MANIFEST = "client-manifest"

private object HttpFixtureReadinessChecker : FixtureReadinessChecker {
    override fun verify(
        process: GatewayProcess,
        descriptor: PinnedFixtureDescriptor,
    ) {
        require(process.isRunning) { "Deterministic Gateway process is not running." }
        val health = get(process.endpoint.resolve(descriptor.value("health_check.path")))
        requireStatus(
            checkName = "health",
            response = health,
            expectedStatus = descriptor.value("health_check.expected_status").toInt(),
        )

        val capabilities = get(process.endpoint.resolve(descriptor.value("capability_check.path")))
        requireStatus(
            checkName = "capability",
            response = capabilities,
            expectedStatus = descriptor.value("capability_check.expected_status").toInt(),
        )
        val manifestKey = descriptor.value("capability_check.required_capabilities")
        require(manifestKey == CLIENT_MANIFEST) {
            "Unsupported capability manifest check '$manifestKey'."
        }
        val advertised =
            try {
                val root = Json.parseToJsonElement(capabilities.body).jsonObject
                root["endpoints"]?.jsonObject ?: error("capability document has no 'endpoints' object")
            } catch (error: Exception) {
                throw FixtureReadinessException(
                    "Capability check response is not a valid capability document.",
                    error,
                )
            }
        PublicBetaGatewayCapabilityManifest.current.requiredEndpoints.forEach { (endpoint, required) ->
            val advertisedEndpoint = advertised[endpoint]?.jsonObject
            val advertisedMethod = advertisedEndpoint?.get("method")?.jsonPrimitive?.content
            val advertisedPath = advertisedEndpoint?.get("path")?.jsonPrimitive?.content
            require(advertisedMethod?.equals(required.method, ignoreCase = true) == true) {
                "Capability check did not report required capability '$endpoint'."
            }
            require(advertisedPath == required.path) {
                "Capability check reported the wrong path for required capability '$endpoint'."
            }
        }
    }

    private fun get(uri: URI): HttpResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResponse(status, body)
        } catch (error: Exception) {
            throw FixtureReadinessException("Readiness request to $uri failed.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireStatus(
        checkName: String,
        response: HttpResponse,
        expectedStatus: Int,
    ) {
        require(response.status == expectedStatus) {
            "$checkName check expected HTTP $expectedStatus but received ${response.status}."
        }
    }

    private data class HttpResponse(
        val status: Int,
        val body: String,
    )
}
