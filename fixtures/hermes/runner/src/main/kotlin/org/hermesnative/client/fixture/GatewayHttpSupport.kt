package org.hermesnative.client.fixture

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder

/** Small HTTP helpers shared by the deterministic fixture Gateway implementations. */
internal object GatewayHttpSupport {
    /**
     * Requests carrying this header are infrastructure probes (readiness checks
     * and telemetry reads by the runner or by the verifier), not application
     * traffic. They are served normally but never recorded, so the recorded
     * request list and every verifier assertion derived from it describes only
     * what the application under test did.
     */
    const val PROBE_HEADER = "X-Journey-Probe"

    fun respond(
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

    fun quote(value: String): String = JsonPrimitive(value).toString()

    fun jsonValue(value: String?): String = value?.let(::quote) ?: "null"

    fun queryParameters(uri: URI): Map<String, String> =
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

    fun recordRequest(
        exchange: HttpExchange,
        requests: MutableList<SyntheticGatewayRequest>,
    ): String? {
        val body = exchange.requestBody.bufferedReader().use { it.readText().takeIf(String::isNotEmpty) }
        if (exchange.requestHeaders.getFirst(PROBE_HEADER) == null) {
            requests +=
                SyntheticGatewayRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    hasAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization") != null,
                    body = body,
                )
        }
        return body
    }
}
