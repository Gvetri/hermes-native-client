package org.hermesnative.client.fixture.journey

import org.hermesnative.client.fixture.PinnedFixtureDescriptor
import java.io.File

/**
 * Entry point that starts one deterministic journey Gateway from an explicit
 * repository-owned scenario. It stays in the foreground until stopped so CI
 * and local runs can manage it as a lifecycle process.
 *
 * Arguments: <scenario-file> [keystore-file] [keystore-password]
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: JourneyGatewayMain <scenario-file> [keystore-file] [keystore-password]" }
    val repositoryRoot =
        System.getProperty("fixture.repositoryRoot")?.let(::File)
            ?: error("fixture.repositoryRoot must identify the repository root.")
    val scenarioFile = File(args[0])
    val keystoreFile =
        args.getOrNull(1)?.let(::File)
            ?: File(repositoryRoot, "fixtures/hermes/journey-tls/journey-gateway.p12")
    val keystorePassword = args.getOrNull(2) ?: DEFAULT_KEYSTORE_PASSWORD
    val descriptor = PinnedFixtureDescriptor.load(File(repositoryRoot, "fixtures/hermes/pinned-fixture.properties"))
    val scenario = JourneyScenarioParser.parse(scenarioFile, descriptor.provenance.value)
    val process = JourneyGatewayProcess.start(scenario, keystoreFile, keystorePassword)
    Runtime.getRuntime().addShutdownHook(Thread(process::stop))
    println("journey-gateway-endpoint=${process.endpoint}")
    println("journey-gateway-scenario=${scenario.name}")
    println("journey-gateway-provenance=${process.provenanceValue}")
    while (process.isRunning) {
        Thread.sleep(STARTUP_POLL_MILLIS)
    }
}

private const val STARTUP_POLL_MILLIS = 1_000L
