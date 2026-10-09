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
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.hermesnative.client.feature.entry.domain.satisfies
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

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
        val endpoints =
            GatewayJsonParser.parseCapabilities(
                operation = "capability discovery",
                root = GatewayJsonParser.parseObject("capability discovery", response.body),
            )
        val capabilities = GatewayCapabilities(endpoints)
        if (!capabilities.satisfies(manifest)) {
            throw GatewayException(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE)
        }
        return capabilities
    }

    override fun listSessions(request: SessionListRequest): SessionPage {
        if (request.limit <= 0) {
            throw GatewayException(
                GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
                "Gateway request failed. Session list limit must be positive.",
            )
        }
        if (request.offset < 0) {
            throw GatewayException(
                GatewayErrorCategory.GATEWAY_REQUEST_FAILED,
                "Gateway request failed. Session list offset must not be negative.",
            )
        }
        val query =
            linkedMapOf(
                "limit" to request.limit.toString(),
                "offset" to request.offset.toString(),
            )
        val response =
            execute(
                operation = "session list",
                method = "GET",
                path = "/api/sessions",
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
                path = "/api/sessions",
                body = body,
                expectedStatus = 201,
            )
        return GatewayJsonParser.parseSessionResponse(
            operation = "session create",
            root = GatewayJsonParser.parseObject("session create", response.body),
        )
    }

    override fun openSession(sessionId: SessionId): Session {
        val response =
            execute(
                operation = "session open",
                method = "GET",
                path = "/api/sessions/${sessionPathSegment(sessionId.value, "session open")}",
                expectedStatus = 200,
            )
        val session =
            GatewayJsonParser.parseSessionResponse(
                operation = "session open",
                root = GatewayJsonParser.parseObject("session open", response.body),
            )
        requireIdentity("session open", "session.id", sessionId.value, session.id.value)
        return session
    }

    override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
        val response =
            execute(
                operation = "session history",
                method = "GET",
                path = "/api/sessions/${sessionPathSegment(sessionId.value, "session history")}/messages",
                expectedStatus = 200,
            )
        val history =
            GatewayJsonParser.parseHistory(
                operation = "session history",
                root = GatewayJsonParser.parseObject("session history", response.body),
            )
        requireIdentity("session history", "session_id", sessionId.value, history.sessionId.value)
        return history
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
                path = "/api/sessions/${sessionPathSegment(sessionId.value, "session rename")}",
                body = body,
                expectedStatus = 200,
            )
        val session =
            GatewayJsonParser.parseSessionResponse(
                operation = "session rename",
                root = GatewayJsonParser.parseObject("session rename", response.body),
            )
        requireIdentity("session rename", "session.id", sessionId.value, session.id.value)
        return session
    }

    override fun deleteSession(sessionId: SessionId) {
        val response =
            execute(
                operation = "session delete",
                method = "DELETE",
                path = "/api/sessions/${sessionPathSegment(sessionId.value, "session delete")}",
                expectedStatus = 200,
            )
        GatewayJsonParser.parseDeletedSession(
            operation = "session delete",
            root = GatewayJsonParser.parseObject("session delete", response.body),
            expectedSessionId = sessionId,
        )
    }

    override fun pinSession(sessionId: SessionId): SessionPinResult = setSessionPinned(sessionId, pinned = true)

    override fun unpinSession(sessionId: SessionId): SessionPinResult = setSessionPinned(sessionId, pinned = false)

    private fun setSessionPinned(
        sessionId: SessionId,
        pinned: Boolean,
    ): SessionPinResult {
        val body = JsonObject(mapOf("pinned" to JsonPrimitive(pinned))).toString()
        val auth = if (pinned) "session pin" else "session unpin"
        val response =
            execute(
                operation = auth,
                method = "PATCH",
                path = "/api/sessions/${sessionPathSegment(sessionId.value, auth)}",
                body = body,
                expectedStatus = 200,
            )
        val session =
            GatewayJsonParser.parseSessionResponse(
                operation = auth,
                root = GatewayJsonParser.parseObject(auth, response.body),
            )
        requireIdentity(auth, "session.id", sessionId.value, session.id.value)
        return SessionPinResult(sessionId = session.id, pinned = session.pinned)
    }

    override fun createRun(
        sessionId: SessionId,
        input: String,
    ): Run {
        val body =
            JsonObject(
                mapOf(
                    "input" to JsonPrimitive(input),
                    "session_id" to JsonPrimitive(sessionId.value),
                ),
            ).toString()
        val response =
            execute(
                operation = "run create",
                method = "POST",
                path = "/v1/runs",
                body = body,
                expectedStatus = 202,
            )
        val admitted =
            GatewayJsonParser.parseRunAdmission(
                operation = "run create",
                root = GatewayJsonParser.parseObject("run create", response.body),
            )
        return Run(id = admitted.runId, sessionId = sessionId, status = admitted.status)
    }

    override fun getRunStatus(runId: RunId): Run {
        val response =
            execute(
                operation = "run status",
                method = "GET",
                path = "/v1/runs/${sessionPathSegment(runId.value, "run status")}",
                expectedStatus = 200,
            )
        val run =
            GatewayJsonParser.parseRunStatus(
                operation = "run status",
                root = GatewayJsonParser.parseObject("run status", response.body),
            )
        requireIdentity("run status", "run_id", runId.value, run.id.value)
        return run
    }

    override fun observeRun(runId: RunId): RunEventObservation {
        val operation = "run observation"
        val gatewayRequest =
            request(
                method = "GET",
                path = "/v1/runs/${sessionPathSegment(runId.value, operation)}/events",
                accept = "text/event-stream",
            )
        return GatewayRunEventObservation(
            operation = operation,
            openStream = {
                val stream = transportCall { transport.openEventStream(gatewayRequest) }
                when {
                    stream.statusCode == 401 || stream.statusCode == 403 -> {
                        stream.close()
                        throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
                    }

                    stream.statusCode != 200 -> {
                        stream.close()
                        throw requestFailure(operation)
                    }

                    else -> stream
                }
            },
            parseFrame = { frame ->
                GatewayJsonParser.parseRunEvent(operation, frame)?.also { event ->
                    requireIdentity(operation, "run_id", runId.value, event.runId.value)
                }
            },
            mapTransportFailure = ::mapTransportFailure,
        )
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
        if (response.statusCode == HTTP_UNAUTHORIZED || response.statusCode == HTTP_FORBIDDEN) {
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

    private fun requireIdentity(
        operation: String,
        field: String,
        expected: String,
        actual: String,
    ) {
        if (actual != expected) {
            throw GatewayException(
                GatewayErrorCategory.INVALID_RESPONSE,
                "Invalid Gateway response for $operation: field '$field' does not match the requested resource.",
            )
        }
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
        if (error is SSLException || error is GeneralSecurityException) {
            GatewayException(GatewayErrorCategory.SECURE_CONNECTION_FAILED)
        } else {
            GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        }
}
