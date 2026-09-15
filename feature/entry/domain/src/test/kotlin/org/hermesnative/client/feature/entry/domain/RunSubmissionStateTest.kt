package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSubmissionStateTest {
    @Test
    fun active_and_unknown_runs_block_a_new_submission() {
        assertFalse(
            RunSubmissionState(
                latestRun = Run(RunId("run-1"), SessionId("session-1"), "running"),
            ).canSubmit,
        )
        assertFalse(
            RunSubmissionState(
                latestRun = Run(RunId("run-2"), SessionId("session-1"), "provider-specific"),
            ).canSubmit,
        )
    }

    @Test
    fun terminal_runs_and_sessions_without_a_run_can_submit() {
        assertTrue(RunSubmissionState().canSubmit)
        assertTrue(
            RunSubmissionState(
                latestRun = Run(RunId("run-1"), SessionId("session-1"), "succeeded"),
            ).canSubmit,
        )
    }

    @Test
    fun any_active_run_blocks_even_when_the_latest_run_is_terminal() {
        assertFalse(
            RunSubmissionState(
                latestRun = Run(RunId("run-2"), SessionId("session-1"), "succeeded"),
                activeRuns = listOf(Run(RunId("run-1"), SessionId("session-1"), "running")),
            ).canSubmit,
        )
    }

    @Test
    fun pending_submission_blocks_duplicate_submission() {
        assertFalse(RunSubmissionState(isSubmissionPending = true).canSubmit)
    }
}
