package org.hermesnative.client.feature.entry.wiring

import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesRunRecoveryStorageTest {
    @Test
    fun round_trip_is_scoped_to_the_current_gateway_endpoint() {
        val context = RuntimeEnvironment.getApplication()
        var endpoint: String? = "https://gateway.example/profile-a"
        val storage = SharedPreferencesRunRecoveryStorage(context) { endpoint }
        val entry = RunRecoveryEntry(SessionId("session-1"), RunId("run-1"))

        storage.save(setOf(entry))
        assertEquals(setOf(entry), storage.load())

        endpoint = "https://gateway.example/profile-b"
        assertTrue(storage.load().isEmpty())

        endpoint = "https://gateway.example/profile-a"
        assertEquals(setOf(entry), storage.load())
    }

    @Test
    fun malformed_persisted_values_are_ignored_without_exposing_transcript_data() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("gateway_run_recovery", 0)
        val storage = SharedPreferencesRunRecoveryStorage(context) { "https://gateway.example/profile" }

        storage.save(emptySet())
        val entriesKey = requireNotNull(preferences.all.keys.single { it.startsWith("entries.") })
        preferences.edit().putStringSet(entriesKey, setOf("not-an-entry", "%%%.")).commit()

        assertTrue(storage.load().isEmpty())
    }
}
