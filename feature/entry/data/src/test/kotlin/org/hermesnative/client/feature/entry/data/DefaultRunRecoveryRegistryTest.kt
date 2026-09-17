package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultRunRecoveryRegistryTest {
    @Test
    fun separate_registries_serialize_read_modify_write_transactions() {
        val first = RunRecoveryEntry(SessionId("session-1"), RunId("run-1"))
        val second = RunRecoveryEntry(SessionId("session-2"), RunId("run-2"))
        val storage = DelayedRunRecoveryStorage()
        val firstRegistry = DefaultRunRecoveryRegistry(storage)
        val secondRegistry = DefaultRunRecoveryRegistry(storage)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstTask =
                executor.submit {
                    ready.countDown()
                    start.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    firstRegistry.save(first)
                }
            val secondTask =
                executor.submit {
                    ready.countDown()
                    start.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    secondRegistry.save(second)
                }
            assertEquals(true, ready.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            start.countDown()
            firstTask.get(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            secondTask.get(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

            assertEquals(listOf(first, second), firstRegistry.load())
        } finally {
            executor.shutdownNow()
        }
    }

    private class DelayedRunRecoveryStorage : RunRecoveryStorage {
        private var entries = emptySet<RunRecoveryEntry>()

        @Synchronized
        override fun load(): Set<RunRecoveryEntry> = entries

        @Synchronized
        override fun save(entries: Set<RunRecoveryEntry>) {
            Thread.sleep(10)
            this.entries = entries
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
