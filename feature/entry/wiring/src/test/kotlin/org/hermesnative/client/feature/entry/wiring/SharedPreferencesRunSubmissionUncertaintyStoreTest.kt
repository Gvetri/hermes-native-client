package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.content.SharedPreferences
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.SessionId
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
    fun unreserved_path_aliases_cannot_start_a_second_submission_attempt() {
        val store = SharedPreferencesRunSubmissionUncertaintyStore(RuntimeEnvironment.getApplication())
        val encoded = PendingRunSubmissionKey("https://gateway.example/profile/%61", SessionId("session-1"))
        val decoded = encoded.copy(endpoint = "https://gateway.example/profile/a")
        store.remove(encoded)
        store.remove(decoded)

        try {
            assertTrue(store.add(encoded, emptySet(), "attempt-1"))
            assertTrue(store.contains(decoded))
            assertFalse(store.add(decoded, emptySet(), "attempt-2"))
        } finally {
            store.remove(encoded)
            store.remove(decoded)
        }
    }

    @Test
    fun an_accepted_add_stays_successful_when_a_listener_removes_the_written_marker() {
        val context = RuntimeEnvironment.getApplication()
        val key = PendingRunSubmissionKey("https://gateway.example/profile", SessionId("session-1"))
        val writer = SharedPreferencesRunSubmissionUncertaintyStore(context)
        val remover = SharedPreferencesRunSubmissionUncertaintyStore(context)
        val preferences = context.getSharedPreferences("gateway_run_submission_uncertainty", Context.MODE_PRIVATE)
        var removed = false
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (remover.remove(key, "attempt-1")) removed = true
            }
        writer.remove(key)
        preferences.registerOnSharedPreferenceChangeListener(listener)

        try {
            val accepted = writer.add(key, emptySet(), "attempt-1")
            assertTrue("The listener must remove the successfully written marker", removed)
            assertTrue("The successful write must remain accepted", accepted)
            assertFalse(writer.contains(key))
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            writer.remove(key)
        }
    }

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

    @Test
    fun removal_requires_the_complete_uncertainty_snapshot_to_match() {
        val context = RuntimeEnvironment.getApplication()
        val key = PendingRunSubmissionKey("https://gateway.example/profile", SessionId("session-1"))
        val store = SharedPreferencesRunSubmissionUncertaintyStore(context)
        store.remove(key)

        try {
            assertTrue(store.add(key, setOf(RunId("known-run")), "attempt-1"))
            val snapshot = requireNotNull(store.snapshot(key))
            assertTrue(store.markAmbiguous(key, "attempt-1"))
            assertFalse(store.removeIfSnapshotMatches(key, snapshot))
            assertTrue(store.contains(key))
            assertTrue(store.removeIfSnapshotMatches(key, requireNotNull(store.snapshot(key))))
            assertFalse(store.contains(key))
        } finally {
            store.remove(key)
        }
    }
}
