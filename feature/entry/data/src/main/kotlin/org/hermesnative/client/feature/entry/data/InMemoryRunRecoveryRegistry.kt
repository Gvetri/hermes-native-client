package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.isValid

class InMemoryRunRecoveryRegistry(
    initialEntries: Iterable<RunRecoveryEntry> = emptyList(),
) : RunRecoveryRegistry {
    private val entries =
        linkedSetOf<RunRecoveryEntry>().apply {
            addAll(initialEntries.filter(RunRecoveryEntry::isValid))
        }

    @Synchronized
    override fun load(): List<RunRecoveryEntry> = entries.toList()

    @Synchronized
    override fun save(entry: RunRecoveryEntry) {
        if (entry.isValid()) entries += entry
    }

    @Synchronized
    override fun remove(entry: RunRecoveryEntry) {
        entries -= entry
    }
}
