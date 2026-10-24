package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.application.normalizeGatewayEndpoint
import org.hermesnative.client.feature.entry.data.AesGcmHistoryCacheCipher
import org.hermesnative.client.feature.entry.data.BoundedHistoryCacheStore
import org.hermesnative.client.feature.entry.domain.HistoryCacheStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Assembles the device-local history cache: Keystore-tier blob storage, the AES-256-GCM
 * cache cipher over that key, and the canonical per-endpoint namespace.
 *
 * Registered by `EntryWiring.createEntryStateHolder`; population and replace-on-refresh
 * wiring live in the Session state holder, and the lifecycle purge is bound by the
 * Settings clear control and the connection-removal flow.
 */
fun buildHistoryCacheStore(context: Context): HistoryCacheStore {
    val storage = AndroidKeyStoreHistoryCacheStorage(context)
    return BoundedHistoryCacheStore(
        storage = storage,
        cipher = AesGcmHistoryCacheCipher(storage::loadOrCreateKey),
        endpointNamespace = ::historyCacheEndpointNamespace,
    )
}

/**
 * The cache namespace for one endpoint: the SHA-256 hex of the normalized Gateway
 * endpoint, falling back to the trimmed input, so entries of one endpoint are never
 * readable as another endpoint's.
 */
internal fun historyCacheEndpointNamespace(endpoint: String): String {
    val canonicalEndpoint = runCatching { normalizeGatewayEndpoint(endpoint) }.getOrDefault(endpoint.trim())
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonicalEndpoint.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }
}

private const val BYTE_MASK = 0xFF
