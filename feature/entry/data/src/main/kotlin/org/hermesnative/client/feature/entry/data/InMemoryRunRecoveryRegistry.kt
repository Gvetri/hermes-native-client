package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry

class InMemoryRunRecoveryRegistry(
    initialEntries: Iterable<RunRecoveryEntry> = emptyList(),
) : RunRecoveryRegistry {
    private val entries = linkedSetOf<RunRecoveryEntry>().apply { addAll(initialEntries) }

    @Synchronized
    override fun load(): List<RunRecoveryEntry> = entries.toList()

    @Synchronized
    override fun save(entry: RunRecoveryEntry) {
        entries += entry
    }

    @Synchronized
    override fun remove(entry: RunRecoveryEntry) {
        entries -= entry
    }

    @Synchronized
    override fun clear() {
        entries.clear()
    }
}
