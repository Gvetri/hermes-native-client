package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryRunRecoveryRegistryTest {
    private val first = RunRecoveryEntry(SessionId("session-1"), RunId("run-1"))
    private val second = RunRecoveryEntry(SessionId("session-2"), RunId("run-2"))

    @Test
    fun saves_minimum_run_identity_metadata_without_duplicates() {
        val registry = InMemoryRunRecoveryRegistry()

        registry.save(first)
        registry.save(first)
        registry.save(second)

        assertEquals(listOf(first, second), registry.load())
    }

    @Test
    fun removes_only_the_entry_that_was_authoritatively_reconciled() {
        val registry = InMemoryRunRecoveryRegistry(listOf(first, second))

        registry.remove(first)

        assertEquals(listOf(second), registry.load())
    }

    @Test
    fun default_registry_round_trips_entries_through_persistent_storage() {
        val storage = FakeRunRecoveryStorage()
        val registry = DefaultRunRecoveryRegistry(storage)

        registry.save(second)
        registry.save(first)

        assertEquals(listOf(first, second), DefaultRunRecoveryRegistry(storage).load())
        registry.remove(first)
        assertEquals(listOf(second), registry.load())
    }

    private class FakeRunRecoveryStorage(
        private var entries: Set<RunRecoveryEntry> = emptySet(),
    ) : RunRecoveryStorage {
        override fun load(): Set<RunRecoveryEntry> = entries

        override fun save(entries: Set<RunRecoveryEntry>) {
            this.entries = entries
        }
    }
}
