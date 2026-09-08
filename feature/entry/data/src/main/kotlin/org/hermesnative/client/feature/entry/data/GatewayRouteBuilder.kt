package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import java.net.URI
import java.net.URLEncoder

internal class GatewayRouteBuilder(
    endpoint: String,
) {
    private val baseUri = parseEndpoint(endpoint)
    private val base = baseUri.toString().trimEnd('/')

    fun route(
        path: String,
        query: Map<String, String?> = emptyMap(),
    ): String {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val queryString =
            query.entries
                .filter { it.value != null }
                .joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(requireNotNull(value))}"
                }
        return buildString {
            append(base)
            append(normalizedPath)
            if (queryString.isNotEmpty()) {
                append('?')
                append(queryString)
            }
        }
    }

    private fun parseEndpoint(endpoint: String): URI {
        val uri =
            try {
                URI(endpoint.trim())
            } catch (_: Exception) {
                throw invalidAddress()
            }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        val loopbackFixture =
            scheme == "http" &&
                host in setOf("127.0.0.1", "localhost", "::1")
        if (
            uri.isOpaque ||
            (scheme != "https" && !loopbackFixture) ||
            host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null
        ) {
            throw invalidAddress()
        }
        return uri
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun invalidAddress(): GatewayException = GatewayException(GatewayErrorCategory.INVALID_ADDRESS)
}
