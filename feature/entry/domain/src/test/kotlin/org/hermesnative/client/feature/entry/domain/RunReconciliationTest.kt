package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunReconciliationTest {
    @Test
    fun a_terminal_run_with_a_matching_history_boundary_is_confirmed() {
        val run = Run(RunId("run-1"), SessionId("session-1"), "succeeded")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages =
                    listOf(
                        GatewayHistoryMessage(
                            id = "message-1",
                            role = "assistant",
                            content = "Authoritative result",
                            runId = run.id,
                            runStatus = "succeeded",
                        ),
                    ),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.CONFIRMED, decideRunReconciliation(run, history))
    }

    @Test
    fun a_completed_run_matches_a_successful_history_boundary() {
        val run = Run(RunId("run-completed"), SessionId("session-1"), "completed")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages = listOf(GatewayHistoryMessage("message-completed", "assistant", "Done", run.id, "succeeded")),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.CONFIRMED, decideRunReconciliation(run, history))
    }

    @Test
    fun a_failed_run_matches_an_error_history_boundary() {
        val run = Run(RunId("run-failed"), SessionId("session-1"), "failed")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages = listOf(GatewayHistoryMessage("message-failed", "assistant", "Failure", run.id, "error")),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.CONFIRMED, decideRunReconciliation(run, history))
    }

    @Test
    fun canceled_and_cancelled_statuses_match_each_other_as_one_terminal_state() {
        listOf("canceled" to "cancelled", "cancelled" to "canceled").forEach { (runStatus, historyStatus) ->
            val run = Run(RunId("run-$runStatus"), SessionId("session-1"), runStatus)
            val history =
                SessionHistory(
                    sessionId = run.sessionId,
                    messages = listOf(GatewayHistoryMessage("message-$runStatus", "assistant", "Cancelled", run.id, historyStatus)),
                    nextCursor = null,
                )

            assertEquals(RunReconciliationDecision.CONFIRMED, decideRunReconciliation(run, history))
        }
    }

    @Test
    fun a_stale_user_history_message_with_the_run_id_does_not_confirm_a_terminal_run() {
        val run = Run(RunId("run-1"), SessionId("session-1"), "completed")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages = listOf(GatewayHistoryMessage("message-1", "user", "Original request", run.id)),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.UNCERTAIN, decideRunReconciliation(run, history))
    }

    @Test
    fun a_terminal_run_with_a_mismatched_terminal_history_status_remains_uncertain() {
        val run = Run(RunId("run-mismatch"), SessionId("session-1"), "failed")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages = listOf(GatewayHistoryMessage("message-mismatch", "assistant", "Success", run.id, "succeeded")),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.UNCERTAIN, decideRunReconciliation(run, history))
    }

    @Test
    fun a_terminal_run_without_a_matching_history_boundary_remains_uncertain() {
        val run = Run(RunId("run-1"), SessionId("session-1"), "succeeded")
        val history = SessionHistory(run.sessionId, emptyList(), null)

        assertEquals(RunReconciliationDecision.UNCERTAIN, decideRunReconciliation(run, history))
    }

    @Test
    fun history_run_id_without_status_is_retained_as_a_conservative_active_run() {
        val sessionId = SessionId("session-1")
        val runId = RunId("run-without-status")
        val history =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-1", "user", "Input", runId, null)),
                nextCursor = null,
            )

        assertEquals(listOf(Run(runId, sessionId, UNKNOWN_RUN_STATUS)), history.runs())
        assertTrue(history.runs().single().isActive())
    }

    @Test
    fun a_later_same_run_message_without_status_preserves_the_latest_known_status() {
        val sessionId = SessionId("session-1")
        val runId = RunId("run-with-known-status")
        val history =
            SessionHistory(
                sessionId = sessionId,
                messages =
                    listOf(
                        GatewayHistoryMessage(
                            id = "message-with-status",
                            role = "assistant",
                            content = "Done",
                            runId = runId,
                            runStatus = "succeeded",
                        ),
                        GatewayHistoryMessage(
                            id = "message-without-status",
                            role = "assistant",
                            content = "Additional metadata",
                            runId = runId,
                            runStatus = null,
                        ),
                    ),
                nextCursor = null,
            )

        assertEquals(listOf(Run(runId, sessionId, "succeeded")), history.runs())
        assertFalse(history.runs().single().isActive())
    }

    @Test
    fun a_later_explicit_status_replaces_an_earlier_status_for_the_same_run() {
        val sessionId = SessionId("session-1")
        val runId = RunId("run-with-status-update")
        val history =
            SessionHistory(
                sessionId = sessionId,
                messages =
                    listOf(
                        GatewayHistoryMessage("started", "user", "Start", runId, "running"),
                        GatewayHistoryMessage("progress", "assistant", "Working", runId, null),
                        GatewayHistoryMessage("failed", "assistant", "Failure", runId, "failed"),
                    ),
                nextCursor = null,
            )

        assertEquals(listOf(Run(runId, sessionId, "failed")), history.runs())
    }

    @Test
    fun distinct_run_ids_keep_their_own_status_and_latest_gateway_message_order() {
        val sessionId = SessionId("session-1")
        val firstRunId = RunId("run-first")
        val secondRunId = RunId("run-second")
        val messages =
            listOf(
                GatewayHistoryMessage(
                    id = "first-result",
                    role = "assistant",
                    content = "First result",
                    runId = firstRunId,
                    runStatus = "succeeded",
                    runResult = "first-result",
                    timestamp = "2026-09-16T10:00:00Z",
                ),
                GatewayHistoryMessage("second-start", "user", "Second request", secondRunId, "running"),
                GatewayHistoryMessage(
                    id = "first-follow-up",
                    role = "assistant",
                    content = "First metadata update",
                    runId = firstRunId,
                    runStatus = null,
                    runResult = "first-result",
                    timestamp = "2026-09-16T10:01:00Z",
                ),
            )
        val history = SessionHistory(sessionId, messages, null)

        assertEquals(
            listOf(
                Run(secondRunId, sessionId, "running"),
                Run(firstRunId, sessionId, "succeeded"),
            ),
            history.runs(),
        )
        assertEquals(messages, history.messages)
    }

    @Test
    fun a_message_without_a_run_id_does_not_synthesize_a_run_from_gateway_text() {
        val sessionId = SessionId("session-1")
        val messages =
            listOf(
                GatewayHistoryMessage(
                    id = "message-without-run-id",
                    role = "assistant",
                    content = "A response mentioning run-first",
                    runId = null,
                    runStatus = "succeeded",
                ),
            )
        val history = SessionHistory(sessionId, messages, null)

        assertTrue(history.runs().isEmpty())
        assertEquals(messages, history.messages)
    }

    @Test
    fun an_active_run_remains_uncertain_even_when_history_contains_the_run() {
        val run = Run(RunId("run-1"), SessionId("session-1"), "running")
        val history =
            SessionHistory(
                sessionId = run.sessionId,
                messages = listOf(GatewayHistoryMessage("message-1", "user", "Input", run.id, "running")),
                nextCursor = null,
            )

        assertEquals(RunReconciliationDecision.UNCERTAIN, decideRunReconciliation(run, history))
    }
}
