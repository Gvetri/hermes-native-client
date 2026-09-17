package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EndpointScopedRunRecoveryRegistryTest {
    @Test
    fun current_and_explicit_endpoint_access_share_cached_registries() {
        var endpoint: String? = "https://gateway-a.example"
        val storages = mutableMapOf<String?, MemoryRunRecoveryStorage>()
        val registry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = { endpoint },
                storageForEndpoint = { value ->
                    MemoryRunRecoveryStorage().also { storages[value] = it }
                },
            )
        val first = RunRecoveryEntry(SessionId("session-a"), RunId("run-a"))
        val second = RunRecoveryEntry(SessionId("session-b"), RunId("run-b"))

        registry.save(first)
        registry.registryForEndpoint("https://gateway-b.example").save(second)
        endpoint = "https://gateway-b.example"

        assertEquals(listOf(first), registry.registryForEndpoint("https://gateway-a.example").load())
        assertEquals(listOf(second), registry.load())
        assertEquals(2, storages.size)
        assertSame(
            registry.registryForEndpoint("https://gateway-a.example"),
            registry.registryForEndpoint("https://gateway-a.example"),
        )
    }

    private class MemoryRunRecoveryStorage : RunRecoveryStorage {
        private var entries = emptySet<RunRecoveryEntry>()

        override fun load(): Set<RunRecoveryEntry> = entries

        override fun save(entries: Set<RunRecoveryEntry>) {
            this.entries = entries
        }
    }
}
