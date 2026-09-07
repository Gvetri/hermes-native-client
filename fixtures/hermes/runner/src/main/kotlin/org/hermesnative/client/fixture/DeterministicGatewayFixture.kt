package org.hermesnative.client.fixture

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
class SyntheticTestState internal constructor() {
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

    internal fun reset() {
        values.clear()
    }
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
) {
    private var descriptor: PinnedFixtureDescriptor? = null
    private var process: GatewayProcess? = null
    private val syntheticState = SyntheticTestState()

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
        } catch (error: Throwable) {
            if (error is FixtureStartupException) {
                throw error
            }
            throw FixtureStartupException("Deterministic Gateway fixture startup failed.", error)
        }
    }

    fun awaitReady() {
        check(lifecycleState == FixtureLifecycleState.STARTED) {
            "Fixture readiness requires a started fixture."
        }
        try {
            readinessChecker.verify(requireProcess(), requireNotNull(descriptor))
            lifecycleState = FixtureLifecycleState.READY
        } catch (error: Throwable) {
            if (error is FixtureReadinessException) {
                throw error
            }
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
        syntheticState.reset()
        lifecycleState = FixtureLifecycleState.TESTING
        var testFailure: Throwable? = null
        return try {
            val activeProcess = requireProcess()
            block(FixtureTestContext(activeProcess.endpoint, activeProcess.provenanceValue, syntheticState))
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            var cleanupFailure: FixtureCleanupException? = null
            try {
                syntheticState.reset()
            } catch (error: Throwable) {
                cleanupFailure =
                    FixtureCleanupException(
                        "Synthetic state reset after the fixture test failed.",
                        listOf(error),
                    )
            }
            lifecycleState = FixtureLifecycleState.READY
            if (cleanupFailure != null) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    fun <T> execute(block: (FixtureTestContext) -> T): T {
        var testFailure: Throwable? = null
        return try {
            setup()
            awaitReady()
            runTest(block)
        } catch (error: Throwable) {
            testFailure = error
            throw error
        } finally {
            try {
                teardown()
            } catch (cleanupFailure: FixtureCleanupException) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    fun teardown() {
        if (lifecycleState == FixtureLifecycleState.TORN_DOWN) {
            return
        }

        val cleanupFailures = mutableListOf<Throwable>()
        try {
            syntheticState.reset()
        } catch (error: Throwable) {
            cleanupFailures += error
        }

        val activeProcess = process
        process = null
        if (activeProcess != null) {
            try {
                activeProcess.stop()
            } catch (error: Throwable) {
                cleanupFailures += error
            }
            if (activeProcess.isRunning) {
                cleanupFailures += IllegalStateException("Deterministic Gateway process did not stop.")
            }
        }
        lifecycleState = FixtureLifecycleState.TORN_DOWN

        if (cleanupFailures.isNotEmpty()) {
            throw FixtureCleanupException("Deterministic Gateway fixture cleanup failed.", cleanupFailures)
        }
    }

    private fun requireProcess(): GatewayProcess = requireNotNull(process) { "Deterministic Gateway fixture is not started." }

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
        val requiredCapability = descriptor.value("capability_check.required_capabilities")
        require(capabilities.body.contains("\"$requiredCapability\"")) {
            "Capability check did not report required capability '$requiredCapability'."
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
