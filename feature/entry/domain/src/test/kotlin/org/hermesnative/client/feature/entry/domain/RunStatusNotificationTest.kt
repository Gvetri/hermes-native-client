package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatusNotificationTest {
    @Test
    fun only_succeeded_and_failed_are_notifiable_terminal_states() {
        assertTrue(RunPresentationState.SUCCEEDED.isNotifiableTerminal())
        assertTrue(RunPresentationState.FAILED.isNotifiableTerminal())
        assertFalse(RunPresentationState.CANCELLED.isNotifiableTerminal())
        assertFalse(RunPresentationState.UNCERTAIN.isNotifiableTerminal())
        assertFalse(RunPresentationState.STARTING.isNotifiableTerminal())
        assertFalse(RunPresentationState.RUNNING.isNotifiableTerminal())
        assertFalse(RunPresentationState.COMPLETING.isNotifiableTerminal())
    }

    @Test
    fun notification_requires_enabled_preference_platform_permission_and_terminal_state() {
        assertTrue(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = true,
                state = RunPresentationState.SUCCEEDED,
            ),
        )
        assertTrue(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = true,
                state = RunPresentationState.FAILED,
            ),
        )
        assertFalse(
            shouldPostRunStatusNotification(
                enabled = false,
                canPost = true,
                state = RunPresentationState.SUCCEEDED,
            ),
        )
        assertFalse(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = false,
                state = RunPresentationState.SUCCEEDED,
            ),
        )
        assertFalse(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = true,
                state = RunPresentationState.CANCELLED,
            ),
        )
        assertFalse(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = true,
                state = RunPresentationState.UNCERTAIN,
            ),
        )
        assertFalse(
            shouldPostRunStatusNotification(
                enabled = true,
                canPost = true,
                state = RunPresentationState.RUNNING,
            ),
        )
    }

    @Test
    fun notification_text_is_fixed_and_derives_only_from_the_presentation_state() {
        assertEquals("Run succeeded", RunPresentationState.SUCCEEDED.runStatusNotificationText())
        assertEquals("Run failed", RunPresentationState.FAILED.runStatusNotificationText())
        assertNull(RunPresentationState.CANCELLED.runStatusNotificationText())
        assertNull(RunPresentationState.UNCERTAIN.runStatusNotificationText())
        assertNull(RunPresentationState.RUNNING.runStatusNotificationText())
        assertNull(RunPresentationState.COMPLETING.runStatusNotificationText())
        assertNull(RunPresentationState.STARTING.runStatusNotificationText())
    }

    @Test
    fun notification_text_never_contains_run_or_session_content() {
        // The mapping accepts only the presentation state, so no Run identifier,
        // Session identifier, raw status, prompt, or response can influence it.
        // Assert the exact produced text for states derived from arbitrary data.
        val run = Run(RunId("run-with-sensitive-ids"), SessionId("session-secret"), "running")
        val derived = run.toRunPresentationState()
        assertEquals("Run failed", RunPresentationState.FAILED.runStatusNotificationText())
        assertFalse(RunPresentationState.FAILED.runStatusNotificationText().orEmpty().contains(run.id.value))
        assertFalse(RunPresentationState.FAILED.runStatusNotificationText().orEmpty().contains(run.sessionId.value))
        assertFalse(derived.isNotifiableTerminal())
        assertNull(derived.runStatusNotificationText())
    }
}
