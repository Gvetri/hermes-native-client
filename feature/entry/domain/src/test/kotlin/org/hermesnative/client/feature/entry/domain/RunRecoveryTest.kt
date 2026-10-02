package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRecoveryTest {
    @Test
    fun a_recovery_entry_needs_both_a_session_and_a_run_identifier() {
        assertTrue(RunRecoveryEntry(SessionId("session-1"), RunId("run-1")).isValid())
        assertFalse(RunRecoveryEntry(SessionId(""), RunId("run-1")).isValid())
        assertFalse(RunRecoveryEntry(SessionId("session-1"), RunId("")).isValid())
        assertFalse(RunRecoveryEntry(SessionId("   "), RunId("run-1")).isValid())
        assertFalse(RunRecoveryEntry(SessionId("session-1"), RunId("   ")).isValid())
    }
}
