package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.isValid

interface RunRecoveryStorage {
    fun load(): Set<RunRecoveryEntry>

    fun save(entries: Set<RunRecoveryEntry>)
}

class DefaultRunRecoveryRegistry(
    private val storage: RunRecoveryStorage,
) : RunRecoveryRegistry {
    override fun load(): List<RunRecoveryEntry> =
        synchronized(storageTransactionLock) {
            storage
                .load()
                .filter(RunRecoveryEntry::isValid)
                .distinct()
                .sortedWith(compareBy({ it.sessionId.value }, { it.runId.value }))
        }

    override fun save(entry: RunRecoveryEntry) {
        if (!entry.isValid()) return
        updateEntries { it + entry }
    }

    override fun remove(entry: RunRecoveryEntry) {
        updateEntries { it - entry }
    }

    private fun updateEntries(transform: (Set<RunRecoveryEntry>) -> Set<RunRecoveryEntry>) {
        synchronized(storageTransactionLock) {
            storage.save(transform(storage.load().filter(RunRecoveryEntry::isValid).toSet()))
        }
    }

    private companion object {
        val storageTransactionLock = Any()
    }
}
