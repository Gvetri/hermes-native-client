package org.hermesnative.client.feature.entry.domain

/**
 * Process-local boundary for an optional Gateway credential.
 *
 * Implementations decide whether the credential is memory-only or protected by device storage.
 */
interface GatewayCredentialStore {
    fun load(): String?

    fun save(credential: String)

    fun clear()
}
