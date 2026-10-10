package org.hermesnative.client.fixture.journey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hermesnative.client.fixture.PinnedFixtureDescriptor
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

internal val repositoryRoot =
    File(requireNotNull(System.getProperty("fixture.repositoryRoot")) { "fixture.repositoryRoot must be set." })

internal val scenariosDir = File(repositoryRoot, "fixtures/hermes/journey/scenarios")

internal val pinnedDescriptor =
    PinnedFixtureDescriptor.load(File(repositoryRoot, "fixtures/hermes/pinned-fixture.properties"))

internal fun startGateway(
    name: String,
    tls: Boolean,
): JourneyGatewayProcess {
    val scenarioFile = File(scenariosDir, "$name.json")
    val scenario = JourneyScenarioParser.parse(scenarioFile, pinnedDescriptor.provenance.value)
    return JourneyGatewayProcess.start(scenario.copy(port = freePort(), tls = tls))
}

internal fun get(
    endpoint: URI,
    path: String,
    bearer: String? = null,
): String {
    val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
    connection.connectTimeout = 2_000
    connection.readTimeout = 5_000
    connection.requestMethod = "GET"
    bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    return runCatching {
        val stream = if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream
        "status=${connection.responseCode} body=${stream.bufferedReader().use { it.readText() }}"
    }.getOrElse { error ->
        "status=${connection.responseCode} error=${error.message}"
    }
}

internal fun getBody(
    endpoint: URI,
    path: String,
): String {
    val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
    connection.connectTimeout = 2_000
    connection.readTimeout = 5_000
    connection.requestMethod = "GET"
    return connection.inputStream.bufferedReader().use { it.readText() }
}

internal fun post(
    endpoint: URI,
    path: String,
    body: String,
): HttpResponse {
    val connection = (endpoint.resolve(path).toURL().openConnection() as HttpURLConnection)
    connection.connectTimeout = 2_000
    connection.readTimeout = 5_000
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
    val responseBody = stream.bufferedReader().use { it.readText() }
    return HttpResponse(connection.responseCode, responseBody)
}

internal fun streamEvents(
    endpoint: URI,
    runId: String,
): List<SseEvent> {
    val connection = (endpoint.resolve("/v1/runs/$runId/events").toURL().openConnection() as HttpURLConnection)
    connection.connectTimeout = 2_000
    connection.readTimeout = 5_000
    connection.requestMethod = "GET"
    val lines = connection.inputStream.bufferedReader().lineSequence().iterator()
    val events = mutableListOf<SseEvent>()
    var lineCount = 0
    while (lines.hasNext() && lineCount < 200) {
        val line = lines.next()
        lineCount++
        if (line.startsWith("data:")) {
            val data = Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
            events += SseEvent(data.getValue("event").jsonPrimitive.content, data.toString())
        }
    }
    return events
}

internal fun tempScenario(content: String): File {
    val file = File.createTempFile("journey-scenario", ".json")
    file.writeText(content)
    file.deleteOnExit()
    return file
}

internal fun freePort(): Int = ServerSocket(0).use { it.localPort }

internal data class HttpResponse(
    val status: Int,
    val body: String,
)

internal data class SseEvent(
    val type: String,
    val data: String,
)
