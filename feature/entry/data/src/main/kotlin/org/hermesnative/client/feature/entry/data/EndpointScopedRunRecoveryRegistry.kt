package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry

/** Shares endpoint-scoped registries so every holder uses the same storage transactions. */
class EndpointScopedRunRecoveryRegistry(
    private val endpointProvider: () -> String?,
    private val storageForEndpoint: (String?) -> RunRecoveryStorage,
) : RunRecoveryRegistry {
    private val registries = mutableMapOf<String?, DefaultRunRecoveryRegistry>()
    private val endpointViews = mutableMapOf<String, RunRecoveryRegistry>()
    private val registryLock = RunRecoveryStorageTransactions.lock

    override fun load(): List<RunRecoveryEntry> = loadForEndpoint(endpointProvider())

    override fun save(entry: RunRecoveryEntry) {
        saveForEndpoint(endpointProvider(), entry)
    }

    fun saveForEndpoint(
        endpoint: String?,
        entry: RunRecoveryEntry,
    ) {
        try {
            synchronized(RunRecoveryStorageTransactions.lock) {
                registry(endpoint).save(entry)
                removeFallbackEntry(endpoint, entry)
            }
        } catch (error: Exception) {
            synchronized(RunRecoveryStorageTransactions.lock) {
                fallbackEntries(endpoint).add(entry)
            }
            throw error
        }
    }

    override fun remove(entry: RunRecoveryEntry) {
        removeForEndpoint(endpointProvider(), entry)
    }

    fun removeForEndpoint(
        endpoint: String?,
        entry: RunRecoveryEntry,
    ) {
        synchronized(RunRecoveryStorageTransactions.lock) {
            registry(endpoint).remove(entry)
            removeFallbackEntry(endpoint, entry)
        }
    }

    fun registryForEndpoint(endpoint: String): RunRecoveryRegistry =
        synchronized(registryLock) {
            endpointViews.getOrPut(endpoint) {
                object : RunRecoveryRegistry {
                    override fun load(): List<RunRecoveryEntry> = loadForEndpoint(endpoint)

                    override fun save(entry: RunRecoveryEntry) {
                        saveForEndpoint(endpoint, entry)
                    }

                    override fun remove(entry: RunRecoveryEntry) {
                        removeForEndpoint(endpoint, entry)
                    }
                }
            }
        }

    private fun loadForEndpoint(endpoint: String?): List<RunRecoveryEntry> =
        synchronized(RunRecoveryStorageTransactions.lock) {
            (registry(endpoint).load() + fallbackEntries(endpoint))
                .distinct()
                .sortedWith(compareBy({ it.sessionId.value }, { it.runId.value }))
        }

    private fun registry(endpoint: String?): DefaultRunRecoveryRegistry =
        synchronized(registryLock) {
            registries.getOrPut(endpoint) {
                DefaultRunRecoveryRegistry(storageForEndpoint(endpoint))
            }
        }

    private fun fallbackEntries(endpoint: String?): MutableSet<RunRecoveryEntry> {
        return pendingEntries.getOrPut(endpoint) { mutableSetOf() }
    }

    private fun removeFallbackEntry(
        endpoint: String?,
        entry: RunRecoveryEntry,
    ) {
        fallbackEntries(endpoint).remove(entry)
        if (pendingEntries[endpoint].isNullOrEmpty()) pendingEntries.remove(endpoint)
    }

    private companion object {
        val pendingEntries = mutableMapOf<String?, MutableSet<RunRecoveryEntry>>()
    }
}
