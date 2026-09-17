package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry

/** Shares endpoint-scoped registries so every holder uses the same storage transactions. */
class EndpointScopedRunRecoveryRegistry(
    private val endpointProvider: () -> String?,
    private val storageForEndpoint: (String?) -> RunRecoveryStorage,
) : RunRecoveryRegistry {
    private val registries = mutableMapOf<String?, DefaultRunRecoveryRegistry>()
    private val registryLock = Any()

    override fun load(): List<RunRecoveryEntry> = currentRegistry().load()

    override fun save(entry: RunRecoveryEntry) {
        currentRegistry().save(entry)
    }

    override fun remove(entry: RunRecoveryEntry) {
        currentRegistry().remove(entry)
    }

    fun registryForEndpoint(endpoint: String): RunRecoveryRegistry = registry(endpoint)

    private fun currentRegistry(): DefaultRunRecoveryRegistry = registry(endpointProvider())

    private fun registry(endpoint: String?): DefaultRunRecoveryRegistry =
        synchronized(registryLock) {
            registries.getOrPut(endpoint) {
                DefaultRunRecoveryRegistry(storageForEndpoint(endpoint))
            }
        }
}
