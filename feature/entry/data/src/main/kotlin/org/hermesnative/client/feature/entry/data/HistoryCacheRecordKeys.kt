package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import java.nio.charset.StandardCharsets

/**
 * The storage-key derivation of the bounded history cache: transcript records address a
 * hex-encoded Session id inside the endpoint namespace; the per-endpoint Session list lives
 * under a reserved slot that hex encoding can never produce, so the two key forms never collide.
 */
internal class HistoryCacheRecordKeys(
    private val endpointNamespace: (String) -> String,
) {
    fun transcript(key: HistoryCacheKey): String {
        val namespace = endpointNamespace(key.endpoint)
        return "$namespace.${encodeSessionId(key.sessionId.value)}"
    }

    fun sessionList(endpoint: String): String = "${endpointNamespace(endpoint)}.$SESSION_LIST_SLOT"

    private fun encodeSessionId(value: String): String =
        value.toByteArray(StandardCharsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val SESSION_LIST_SLOT = "session-list"
    }
}
