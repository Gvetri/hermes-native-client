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
}
