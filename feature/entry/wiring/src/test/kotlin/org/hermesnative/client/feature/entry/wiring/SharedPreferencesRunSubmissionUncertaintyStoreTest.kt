package org.hermesnative.client.feature.entry.wiring

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.presentation.PendingRunSubmissionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesRunSubmissionUncertaintyStoreTest {
    @Test
    fun a_pre_send_marker_survives_store_recreation_and_keeps_only_identifier_state() {
        val context = RuntimeEnvironment.getApplication()
        val key = PendingRunSubmissionKey("https://gateway.example/profile", SessionId("session-1"))
        val knownRunId = RunId("known-run")
        val submittedRunId = RunId("submitted-run")
        val store = SharedPreferencesRunSubmissionUncertaintyStore(context)
        store.remove(key)

        try {
            store.add(key, setOf(knownRunId))
            store.bindRun(key, submittedRunId)
            store.markAmbiguous(key)

            val recreated = SharedPreferencesRunSubmissionUncertaintyStore(context)
            assertTrue(recreated.contains(key))
            assertEquals(setOf(knownRunId), recreated.knownRunIds(key))
            assertEquals(submittedRunId, recreated.boundRunId(key))
            assertFalse(recreated.isSettled(key))
            assertTrue(recreated.requiresRunMatch(key))
        } finally {
            store.remove(key)
        }
    }

    @Test
    fun an_older_attempt_cannot_bind_or_remove_a_newer_marker() {
        val context = RuntimeEnvironment.getApplication()
        val key = PendingRunSubmissionKey("https://gateway.example/profile", SessionId("session-1"))
        val firstRunId = RunId("first-run")
        val secondRunId = RunId("second-run")
        val store = SharedPreferencesRunSubmissionUncertaintyStore(context)
        store.remove(key)

        try {
            assertTrue(store.add(key, emptySet(), "attempt-1"))
            assertTrue(store.remove(key, "attempt-1"))
            assertTrue(store.add(key, setOf(firstRunId), "attempt-2"))
            assertFalse(store.add(key, emptySet(), "attempt-1"))
            assertFalse(store.bindRun(key, secondRunId, "attempt-1"))
            assertEquals(null, store.boundRunId(key))
            assertFalse(store.removeIfKnownRunIdsMatch(key, setOf(firstRunId), "attempt-1"))
            assertTrue(store.contains(key))
            assertTrue(store.bindRun(key, secondRunId, "attempt-2"))
            assertTrue(store.removeIfKnownRunIdsMatch(key, setOf(firstRunId), "attempt-2"))
            assertFalse(store.contains(key))
        } finally {
            store.remove(key)
        }
    }
}
