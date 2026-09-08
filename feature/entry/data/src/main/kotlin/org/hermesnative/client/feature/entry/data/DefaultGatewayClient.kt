package org.hermesnative.client.feature.entry.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.GatewayContractPort
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

class DefaultGatewayClient(
    endpoint: String,
    bearerToken: String,
    private val transport: GatewayTransport = OkHttpGatewayTransport(),
    private val manifest: GatewayCapabilityManifest = PublicBetaGatewayCapabilityManifest.current,
) : GatewayContractPort {
    private val routes = GatewayRouteBuilder(endpoint)
    private val authorizationHeader: String

    init {
        if (bearerToken.isBlank()) {
            throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
        }
        authorizationHeader = "Bearer $bearerToken"
    }

    override fun discoverCapabilities(): GatewayCapabilities {
        val response =
            execute(
                operation = "capability discovery",
                method = "GET",
                path = manifest.discoveryPath,
                expectedStatus = 200,
            )
        val identifiers =
            GatewayJsonParser.parseCapabilities(
                operation = "capability discovery",
                root = GatewayJsonParser.parseObject("capability discovery", response.body),
            )
        if (!manifest.requiredIdentifiers.all(identifiers::contains)) {
            throw GatewayException(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE)
        }
        return GatewayCapabilities(identifiers)
    }

    override fun listSessions(request: SessionListRequest): SessionPage {
        if (request.limit <= 0) {
            throw GatewayException(
                GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
                "Gateway request failed. Session list limit must be positive.",
            )
        }
        val query =
            linkedMapOf(
                "limit" to request.limit.toString(),
                "cursor" to request.cursor,
                "search" to request.search,
            )
        val response =
            execute(
                operation = "session list",
                method = "GET",
                path = "/v1/sessions",
                query = query,
                expectedStatus = 200,
            )
        return GatewayJsonParser.parseSessionPage(
            operation = "session list",
            root = GatewayJsonParser.parseObject("session list", response.body),
        )
    }

    override fun createSession(title: String?): Session {
        val body = JsonObject(mapOf("title" to (title?.let(::JsonPrimitive) ?: JsonNull))).toString()
        val response =
            execute(
                operation = "session create",
                method = "POST",
                path = "/v1/sessions",
                body = body,
                expectedStatus = 201,
            )
        return GatewayJsonParser.parseSession(
            operation = "session create",
            root = GatewayJsonParser.parseObject("session create", response.body),
        )
    }

    override fun openSession(sessionId: SessionId): Session {
        val response =
            execute(
                operation = "session open",
                method = "GET",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session open")}",
                expectedStatus = 200,
            )
        return GatewayJsonParser.parseSession(
            operation = "session open",
            root = GatewayJsonParser.parseObject("session open", response.body),
        )
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        val response =
            execute(
                operation = "session history",
                method = "GET",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session history")}/history",
                expectedStatus = 200,
            )
        return GatewayJsonParser.parseHistory(
            operation = "session history",
            root = GatewayJsonParser.parseObject("session history", response.body),
        )
    }

    override fun renameSession(
        sessionId: SessionId,
        title: String,
    ): Session {
        val body = JsonObject(mapOf("title" to JsonPrimitive(title))).toString()
        val response =
            execute(
                operation = "session rename",
                method = "PATCH",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session rename")}",
                body = body,
                expectedStatus = 200,
            )
        return GatewayJsonParser.parseSession(
            operation = "session rename",
            root = GatewayJsonParser.parseObject("session rename", response.body),
        )
    }

    override fun deleteSession(sessionId: SessionId) {
        execute(
            operation = "session delete",
            method = "DELETE",
            path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session delete")}",
            expectedStatus = 204,
        )
    }

    override fun pinSession(sessionId: SessionId): SessionPinResult {
        val response =
            execute(
                operation = "session pin",
                method = "POST",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session pin")}/pin",
                expectedStatus = 200,
            )
        return GatewayJsonParser.parsePinResult(
            operation = "session pin",
            root = GatewayJsonParser.parseObject("session pin", response.body),
        )
    }

    override fun unpinSession(sessionId: SessionId): SessionPinResult {
        val response =
            execute(
                operation = "session unpin",
                method = "DELETE",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "session unpin")}/pin",
                expectedStatus = 200,
            )
        return GatewayJsonParser.parsePinResult(
            operation = "session unpin",
            root = GatewayJsonParser.parseObject("session unpin", response.body),
        )
    }

    override fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run {
        val body = JsonObject(mapOf("input" to JsonPrimitive(input))).toString()
        val response =
            execute(
                operation = "run create",
                method = "POST",
                path = "/v1/sessions/${sessionPathSegment(sessionId.value, "run create")}/runs",
                body = body,
                expectedStatus = 202,
            )
        return GatewayJsonParser.parseRun(
            operation = "run create",
            root = GatewayJsonParser.parseObject("run create", response.body),
        )
    }

    override fun getRunStatus(runId: RunId): Run {
        val response =
            execute(
                operation = "run status",
                method = "GET",
                path = "/v1/runs/${sessionPathSegment(runId.value, "run status")}",
                expectedStatus = 200,
            )
        return GatewayJsonParser.parseRun(
            operation = "run status",
            root = GatewayJsonParser.parseObject("run status", response.body),
        )
    }

    override fun observeRun(runId: RunId): Sequence<org.hermesnative.client.feature.entry.domain.RunEvent> {
        val operation = "run observation"
        val request =
            request(
                method = "GET",
                path = "/v1/runs/${sessionPathSegment(runId.value, operation)}/events",
                accept = "text/event-stream",
            )
        val stream = transportCall { transport.openEventStream(request) }
        if (stream.statusCode == 401 || stream.statusCode == 403) {
            stream.close()
            throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
        }
        if (stream.statusCode != 200) {
            stream.close()
            throw requestFailure(operation)
        }
        return sequence {
            try {
                GatewaySseParser.frames(stream.lines, operation).forEach { frame ->
                    val event = GatewayJsonParser.parseRunEvent(operation, frame)
                    if (event != null) yield(event)
                }
            } catch (error: GatewayException) {
                throw error
            } catch (error: Exception) {
                throw mapTransportFailure(error)
            } finally {
                stream.close()
            }
        }
    }

    private fun execute(
        operation: String,
        method: String,
        path: String,
        expectedStatus: Int,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
    ): GatewayHttpResponse {
        val response =
            transportCall {
                transport.execute(request(method, path, query, body))
            }
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
        }
        if (response.statusCode != expectedStatus) {
            throw requestFailure(operation)
        }
        return response
    }

    private fun request(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        accept: String = "application/json",
    ): GatewayHttpRequest =
        GatewayHttpRequest(
            method = method,
            url = routes.route(path, query),
            headers =
                buildMap {
                    put("Accept", accept)
                    put("Authorization", authorizationHeader)
                    if (body != null) put("Content-Type", "application/json")
                },
            body = body,
        )

    private fun sessionPathSegment(
        value: String,
        operation: String,
    ): String {
        if (value.isBlank() || value.any { it == '/' || it == '?' || it == '#' }) {
            throw GatewayException(
                GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
                "Gateway request failed. The $operation identifier is invalid.",
            )
        }
        return value
    }

    private fun requestFailure(operation: String): GatewayException =
        GatewayException(
            GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
            "Gateway request failed during $operation. Try again.",
        )

    private inline fun <T> transportCall(block: () -> T): T =
        try {
            block()
        } catch (error: GatewayException) {
            throw error
        } catch (error: Exception) {
            throw mapTransportFailure(error)
        }

    private fun mapTransportFailure(error: Exception): GatewayException =
        if (error is SSLException || error is CertificateException) {
            GatewayException(GatewayErrorCategory.SECURE_CONNECTION_FAILED)
        } else {
            GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        }
}
