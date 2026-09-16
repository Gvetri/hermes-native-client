package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
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
    fun canceled_and_cancelled_runs_with_matching_history_are_confirmed() {
        listOf("canceled", "cancelled").forEach { status ->
            val run = Run(RunId("run-$status"), SessionId("session-1"), status)
            val history =
                SessionHistory(
                    sessionId = run.sessionId,
                    messages = listOf(GatewayHistoryMessage("message-$status", "assistant", "Result", run.id, status)),
                    nextCursor = null,
                )

            assertEquals(RunReconciliationDecision.CONFIRMED, decideRunReconciliation(run, history))
        }
    }

    @Test
    fun a_terminal_run_without_a_matching_history_boundary_remains_uncertain() {
        val run = Run(RunId("run-1"), SessionId("session-1"), "succeeded")
        val history = SessionHistory(run.sessionId, emptyList(), null)

        assertEquals(RunReconciliationDecision.UNCERTAIN, decideRunReconciliation(run, history))
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
