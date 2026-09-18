package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun a_failed_save_is_available_to_a_recreated_endpoint_registry_until_it_is_durable() {
        val storage = FailingRunRecoveryStorage()
        val entry = RunRecoveryEntry(SessionId("session"), RunId("run"))
        val firstRegistry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = { "https://gateway.example" },
                storageForEndpoint = { storage },
            )

        try {
            firstRegistry.save(entry)
            fail("Expected the storage failure")
        } catch (_: IllegalStateException) {
            // The process-local fallback must retain the identifier-only entry.
        }

        val recreatedRegistry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = { "https://gateway.example" },
                storageForEndpoint = { storage },
            )
        assertEquals(listOf(entry), recreatedRegistry.load())

        storage.failWrites = false
        recreatedRegistry.save(entry)
        assertEquals(listOf(entry), recreatedRegistry.load())
        recreatedRegistry.remove(entry)
        assertEquals(emptyList<RunRecoveryEntry>(), recreatedRegistry.load())
    }

    @Test
    fun separate_endpoint_registries_serialize_shared_storage_transactions() {
        val storage = ConcurrentRunRecoveryStorage()
        val firstRegistry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = { "https://gateway.example" },
                storageForEndpoint = { storage },
            )
        val secondRegistry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = { "https://gateway.example" },
                storageForEndpoint = { storage },
            )
        val first = RunRecoveryEntry(SessionId("session-a"), RunId("run-a"))
        val second = RunRecoveryEntry(SessionId("session-b"), RunId("run-b"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstSave =
                executor.submit {
                    start.await()
                    firstRegistry.save(first)
                }
            val secondSave =
                executor.submit {
                    start.await()
                    secondRegistry.save(second)
                }
            start.countDown()
            firstSave.get(2, TimeUnit.SECONDS)
            secondSave.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, storage.maximumConcurrentLoads.get())
        assertEquals(setOf(first, second), firstRegistry.load().toSet())
    }

    private class MemoryRunRecoveryStorage : RunRecoveryStorage {
        private var entries = emptySet<RunRecoveryEntry>()

        override fun load(): Set<RunRecoveryEntry> = entries

        override fun save(entries: Set<RunRecoveryEntry>) {
            this.entries = entries
        }
    }

    private class FailingRunRecoveryStorage : RunRecoveryStorage {
        var failWrites = true
        private var entries = emptySet<RunRecoveryEntry>()

        override fun load(): Set<RunRecoveryEntry> = entries

        override fun save(entries: Set<RunRecoveryEntry>) {
            if (failWrites) throw IllegalStateException("write failed")
            this.entries = entries
        }
    }

    private class ConcurrentRunRecoveryStorage : RunRecoveryStorage {
        private var entries = emptySet<RunRecoveryEntry>()
        private val activeLoads = AtomicInteger()
        val maximumConcurrentLoads = AtomicInteger()

        override fun load(): Set<RunRecoveryEntry> {
            val active = activeLoads.incrementAndGet()
            maximumConcurrentLoads.updateAndGet { current -> maxOf(current, active) }
            Thread.sleep(25)
            activeLoads.decrementAndGet()
            return entries
        }

        override fun save(entries: Set<RunRecoveryEntry>) {
            this.entries = entries
        }
    }
}
