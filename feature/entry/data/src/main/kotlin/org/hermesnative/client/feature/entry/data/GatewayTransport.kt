package org.hermesnative.client.feature.entry.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GatewayHttpRequest(
    val method: String,
    val url: String,
    headers: Map<String, String>,
    val body: String? = null,
) {
    val headers: Map<String, String> = headers.toMap()

    override fun toString(): String = "GatewayHttpRequest(method=$method, url=$url)"
}

class GatewayHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    override fun toString(): String = "GatewayHttpResponse(statusCode=$statusCode)"
}

class GatewayEventStream(
    val statusCode: Int,
    val lines: Sequence<String>,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            closeAction()
        }
    }
}

interface GatewayTransport {
    fun execute(request: GatewayHttpRequest): GatewayHttpResponse

    fun openEventStream(request: GatewayHttpRequest): GatewayEventStream
}

class OkHttpGatewayTransport(
    private val client: OkHttpClient = OkHttpClient(),
) : GatewayTransport {
    override fun execute(request: GatewayHttpRequest): GatewayHttpResponse {
        client.newCall(request(request)).execute().use { response ->
            return GatewayHttpResponse(
                statusCode = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream {
        val response = client.newCall(request(request)).execute()
        val source = response.body?.source()
        if (source == null) {
            return GatewayEventStream(response.code, emptySequence(), response::close)
        }

        val lines =
            sequence {
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        yield(line)
                    }
                } finally {
                    response.close()
                }
            }
        return GatewayEventStream(response.code, lines, response::close)
    }

    private fun request(request: GatewayHttpRequest): Request {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val body = request.body?.toRequestBody(JSON_MEDIA_TYPE)
        val requestBody = if (request.method in METHODS_WITHOUT_BODY) null else body
        return builder.method(request.method, requestBody).build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val METHODS_WITHOUT_BODY = setOf("GET", "DELETE", "HEAD")
    }
}
