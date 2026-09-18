package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultRunSubmissionUncertaintyStoreTest {
    private val key = PendingRunSubmissionKey("https://gateway.example/profile", SessionId("session-1"))

    @Test
    fun an_older_attempt_cannot_update_or_remove_a_newer_marker() {
        val store = DefaultRunSubmissionUncertaintyStore(InMemoryStorage())
        val known = RunId("known-run")
        val submitted = RunId("submitted-run")

        assertTrue(store.add(key, setOf(known), "current"))
        assertFalse(store.add(key, emptySet(), "old"))
        assertFalse(store.bindRun(key, submitted, "old"))
        assertFalse(store.markSettled(key, "old"))
        assertFalse(store.markAmbiguous(key, "old"))
        assertFalse(store.remove(key, "old"))
        assertFalse(store.removeIfKnownRunIdsMatch(key, setOf(known), "old"))
        assertEquals("current", store.attemptId(key))
        assertEquals(setOf(known), store.knownRunIds(key))
        assertEquals(null, store.boundRunId(key))
        assertFalse(store.isSettled(key))
        assertFalse(store.requiresRunMatch(key))
    }

    @Test
    fun removal_requires_the_complete_latest_uncertainty_snapshot() {
        val store = DefaultRunSubmissionUncertaintyStore(InMemoryStorage())
        val runId = RunId("submitted-run")
        assertTrue(store.add(key, emptySet(), "attempt-1"))
        val initial = requireNotNull(store.snapshot(key))

        assertTrue(store.bindRun(key, runId, "attempt-1"))
        assertTrue(store.markSettled(key, "attempt-1"))
        assertTrue(store.markAmbiguous(key, "attempt-1"))
        assertFalse(store.removeIfSnapshotMatches(key, initial))
        assertEquals(runId, store.boundRunId(key))
        assertTrue(store.isSettled(key))
        assertTrue(store.requiresRunMatch(key))
        assertTrue(store.removeIfSnapshotMatches(key, requireNotNull(store.snapshot(key))))
        assertFalse(store.contains(key))
    }

    @Test
    fun a_storage_failure_does_not_report_an_accepted_submission() {
        val storage = InMemoryStorage().apply { failWrites = true }
        val store = DefaultRunSubmissionUncertaintyStore(storage)

        assertThrows(IllegalStateException::class.java) { store.add(key, emptySet(), "attempt-1") }
        assertFalse(store.contains(key))
    }

    @Test
    fun separate_stores_serialize_updates_to_the_same_attempt() {
        val storage = InMemoryStorage()
        val first = DefaultRunSubmissionUncertaintyStore(storage)
        val second = DefaultRunSubmissionUncertaintyStore(storage)
        val firstRun = RunId("first-run")
        val secondRun = RunId("second-run")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)

        try {
            val firstUpdate =
                workers.submit<Boolean> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    first.add(key, setOf(firstRun), "attempt-1")
                }
            val secondUpdate =
                workers.submit<Boolean> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    second.add(key, setOf(secondRun), "attempt-1")
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertTrue(firstUpdate.get(5, TimeUnit.SECONDS))
            assertTrue(secondUpdate.get(5, TimeUnit.SECONDS))
            assertEquals(setOf(firstRun, secondRun), first.knownRunIds(key))
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class InMemoryStorage : RunSubmissionUncertaintyStorage {
        private val records = mutableMapOf<PendingRunSubmissionKey, RunSubmissionUncertaintySnapshot>()
        var failWrites = false

        override fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? = records[key]

        override fun write(
            key: PendingRunSubmissionKey,
            snapshot: RunSubmissionUncertaintySnapshot,
        ) {
            check(!failWrites) { "Storage is unavailable." }
            records[key] = snapshot
        }

        override fun remove(key: PendingRunSubmissionKey) {
            records.remove(key)
        }
    }
}
