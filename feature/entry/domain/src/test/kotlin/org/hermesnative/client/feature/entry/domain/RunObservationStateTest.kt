package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunObservationStateTest {
    private val run = Run(RunId("run-1"), SessionId("session-1"), "starting")

    @Test
    fun supported_lifecycle_and_text_events_transition_to_a_succeeded_non_streaming_response() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.STARTED, status = "starting", eventId = "1"))
                .transition(event(RunEventType.RUNNING, status = "running", eventId = "2"))
                .transition(
                    event(
                        RunEventType.MESSAGE_DELTA,
                        status = "running",
                        text = "Hello",
                        eventId = "3",
                    ),
                )
                .transition(
                    event(
                        RunEventType.MESSAGE_DELTA,
                        status = "running",
                        text = " world",
                        eventId = "4",
                    ),
                )
                .transition(event(RunEventType.COMPLETING, status = "completing", eventId = "5"))
                .transition(event(RunEventType.COMPLETED, status = "succeeded", eventId = "6"))

        assertEquals(RunPresentationState.SUCCEEDED, state.state)
        assertEquals("Hello world", state.responseText)
        assertFalse(state.isStreaming)
        assertFalse(state.isStreamInterrupted)
    }

    @Test
    fun failed_and_unknown_terminal_events_never_report_success() {
        val failed =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.FAILED, status = "failed", eventId = "failed"))
        val uncertain =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.COMPLETED, status = "future-status", eventId = "unknown"))

        assertEquals(RunPresentationState.FAILED, failed.state)
        assertEquals(RunPresentationState.UNCERTAIN, uncertain.state)
        assertFalse(uncertain.isStreaming)
    }

    @Test
    fun a_completed_canceled_event_maps_to_cancelled() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.COMPLETED, status = "canceled", eventId = "canceled"))

        assertEquals(RunPresentationState.CANCELLED, state.state)
        assertFalse(state.isStreaming)
    }

    @Test
    fun a_completed_cancelled_event_maps_to_cancelled() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.COMPLETED, status = "cancelled", eventId = "cancelled"))

        assertEquals(RunPresentationState.CANCELLED, state.state)
        assertFalse(state.isStreaming)
    }

    @Test
    fun duplicate_event_identity_is_ignored_without_repeating_text_or_a_transition() {
        val delta =
            event(
                RunEventType.MESSAGE_DELTA,
                status = "running",
                text = "once",
                eventId = "delta-1",
            )
        val afterFirst = RunEventStateTransition.initial(run).transition(delta)
        val afterDuplicate = afterFirst.transition(delta)

        assertEquals(afterFirst, afterDuplicate)
        assertEquals("once", afterDuplicate.responseText)
        assertTrue(afterDuplicate.isStreaming)
    }

    @Test
    fun an_interrupted_observation_is_uncertain_and_never_succeeded() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.RUNNING, status = "running", eventId = "1"))
                .transition(
                    event(
                        RunEventType.MESSAGE_DELTA,
                        status = "running",
                        text = "Partial",
                        eventId = "2",
                    ),
                )
                .interrupted()

        assertEquals(RunPresentationState.UNCERTAIN, state.state)
        assertEquals("Partial", state.responseText)
        assertFalse(state.isStreaming)
        assertTrue(state.isStreamInterrupted)
    }

    @Test
    fun an_unknown_initial_run_is_uncertain_without_claiming_that_it_is_streaming() {
        val state = RunEventStateTransition.initial(run.copy(status = "future-status"))

        assertEquals(RunPresentationState.UNCERTAIN, state.state)
        assertFalse(state.isStreaming)
    }

    @Test
    fun canceled_statuses_are_confirmed_terminal_states() {
        listOf("canceled", "cancelled").forEach { status ->
            val state = RunEventStateTransition.initial(run.copy(status = status))

            assertEquals(RunPresentationState.CANCELLED, state.state)
            assertTrue(state.state.isTerminal())
            assertFalse(state.isStreaming)
        }
    }

    @Test
    fun an_interruption_does_not_confirm_that_the_remote_run_is_terminal() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.INTERRUPTED, status = "running", eventId = "interrupted"))

        assertEquals(RunPresentationState.UNCERTAIN, state.state)
        assertTrue(state.run.isActive())
    }

    @Test
    fun a_gateway_reported_interrupted_status_is_a_terminal_run() {
        assertEquals(RunPresentationState.CANCELLED, "interrupted".toRunPresentationState())
        assertFalse(Run(run.id, run.sessionId, "interrupted").isActive())
    }

    @Test
    fun every_pinned_lifecycle_status_synonym_maps_to_its_presentation_state() {
        val expectedByStatus =
            mapOf(
                "queued" to RunPresentationState.STARTING,
                "starting" to RunPresentationState.STARTING,
                "started" to RunPresentationState.STARTING,
                "running" to RunPresentationState.RUNNING,
                "in_progress" to RunPresentationState.RUNNING,
                "in-progress" to RunPresentationState.RUNNING,
                "waiting_for_approval" to RunPresentationState.RUNNING,
                "completing" to RunPresentationState.COMPLETING,
                "finalizing" to RunPresentationState.COMPLETING,
                "stopping" to RunPresentationState.COMPLETING,
                "completed" to RunPresentationState.SUCCEEDED,
                "complete" to RunPresentationState.SUCCEEDED,
                "succeeded" to RunPresentationState.SUCCEEDED,
                "success" to RunPresentationState.SUCCEEDED,
                "failed" to RunPresentationState.FAILED,
                "failure" to RunPresentationState.FAILED,
                "error" to RunPresentationState.FAILED,
                "cancelled" to RunPresentationState.CANCELLED,
                "canceled" to RunPresentationState.CANCELLED,
                "interrupted" to RunPresentationState.CANCELLED,
            )

        expectedByStatus.forEach { (status, expected) ->
            assertEquals("status '$status'", expected, status.toRunPresentationState())
            assertEquals(
                "status '$status' on a Run",
                expected,
                Run(run.id, run.sessionId, status).toRunPresentationState(),
            )
        }
        assertEquals(RunPresentationState.UNCERTAIN, "future-status".toRunPresentationState())
    }

    @Test
    fun status_matching_ignores_surrounding_whitespace_and_letter_case() {
        assertEquals(RunPresentationState.RUNNING, "  In_Progress  ".toRunPresentationState())
        assertEquals(RunPresentationState.CANCELLED, "Interrupted".toRunPresentationState())
        assertEquals(RunPresentationState.UNCERTAIN, "   ".toRunPresentationState())
    }

    @Test
    fun waiting_for_approval_is_a_live_run_that_is_not_yet_terminal() {
        val waiting = Run(run.id, run.sessionId, "waiting_for_approval")

        assertEquals(RunPresentationState.RUNNING, waiting.toRunPresentationState())
        assertTrue(waiting.isActive())
        assertFalse(waiting.toRunPresentationState().isTerminal())
        assertTrue(RunEventStateTransition.initial(waiting).isStreaming)
    }

    @Test
    fun text_delta_events_append_to_the_response_like_message_deltas() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.TEXT_DELTA, status = "running", text = "Part", eventId = "text-1"))
                .transition(event(RunEventType.TEXT_DELTA, status = "running", text = " two", eventId = "text-2"))

        assertEquals(RunPresentationState.RUNNING, state.state)
        assertEquals("Part two", state.responseText)
        assertTrue(state.isStreaming)
    }

    @Test
    fun succeeded_and_cancelled_event_types_settle_the_run_as_terminal() {
        val succeeded =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.SUCCEEDED, status = "succeeded", eventId = "succeeded-event"))
        val cancelled =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.CANCELLED, status = "cancelled", eventId = "cancelled-event"))

        assertEquals(RunPresentationState.SUCCEEDED, succeeded.state)
        assertFalse(succeeded.isStreaming)
        assertEquals(RunPresentationState.CANCELLED, cancelled.state)
        assertTrue(cancelled.state.isTerminal())
        assertFalse(cancelled.isStreaming)
    }

    @Test
    fun completed_events_resolve_every_terminal_status_family_and_an_unknown_status() {
        val expectedByStatus =
            mapOf(
                "" to RunPresentationState.SUCCEEDED,
                "completed" to RunPresentationState.SUCCEEDED,
                "complete" to RunPresentationState.SUCCEEDED,
                "succeeded" to RunPresentationState.SUCCEEDED,
                "success" to RunPresentationState.SUCCEEDED,
                "failed" to RunPresentationState.FAILED,
                "failure" to RunPresentationState.FAILED,
                "error" to RunPresentationState.FAILED,
                "cancelled" to RunPresentationState.CANCELLED,
                "canceled" to RunPresentationState.CANCELLED,
                "interrupted" to RunPresentationState.CANCELLED,
                "future-status" to RunPresentationState.UNCERTAIN,
            )

        expectedByStatus.forEach { (status, expected) ->
            val state =
                RunEventStateTransition.initial(run)
                    .transition(event(RunEventType.COMPLETED, status = status, eventId = "completed-$status"))

            assertEquals("completed status '$status'", expected, state.state)
            assertFalse("completed status '$status' must stop streaming", state.isStreaming)
        }
    }

    @Test
    fun an_event_for_another_run_never_mutates_the_observation() {
        val initial = RunEventStateTransition.initial(run)
        val foreign = event(RunEventType.MESSAGE_DELTA, status = "succeeded", text = "other", eventId = "foreign")
        val state = initial.transition(foreign.copy(runId = RunId("run-2")))

        assertEquals(initial, state)
        assertEquals("", state.responseText)
        assertEquals("starting", state.run.status)
    }

    @Test
    fun events_without_a_usable_event_identity_are_applied_without_deduplication() {
        val absentId = RunEvent(RunEventType.TEXT_DELTA, run.id, status = "running", text = "chunk")
        val afterAbsentId = RunEventStateTransition.initial(run).transition(absentId).transition(absentId)
        val blankId = RunEvent(RunEventType.TEXT_DELTA, run.id, status = "running", text = "x", eventId = "   ")
        val afterBlankId = RunEventStateTransition.initial(run).transition(blankId).transition(blankId)

        assertEquals("chunkchunk", afterAbsentId.responseText)
        assertTrue(afterAbsentId.processedEventIds.isEmpty())
        assertEquals("xx", afterBlankId.responseText)
        assertTrue(afterBlankId.processedEventIds.isEmpty())
    }

    @Test
    fun a_blank_event_status_preserves_the_runs_last_known_status() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(RunEvent(RunEventType.RUNNING, run.id, status = "   ", eventId = "blank-status"))

        assertEquals(RunPresentationState.RUNNING, state.state)
        assertEquals("starting", state.run.status)
    }

    @Test
    fun a_delta_without_text_appends_nothing_and_keeps_a_settled_observation_stopped() {
        val settled =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.COMPLETED, status = "succeeded", eventId = "settled"))
        val state =
            settled.transition(
                RunEvent(RunEventType.MESSAGE_DELTA, run.id, status = "running", eventId = "empty-delta"),
            )

        assertEquals("", state.responseText)
        assertFalse(state.isStreaming)
        assertEquals(RunPresentationState.RUNNING, state.state)
    }

    @Test
    fun a_text_bearing_event_marks_an_uncertain_observation_as_streaming_again() {
        val uncertain = RunEventStateTransition.initial(run.copy(status = "future-status"))
        val state =
            uncertain.transition(
                RunEvent(RunEventType.RUNNING, run.id, status = "running", text = "resumed", eventId = "resume"),
            )

        assertEquals(RunPresentationState.RUNNING, state.state)
        assertTrue(state.isStreaming)
    }

    @Test
    fun an_interruption_never_rewrites_a_settled_or_already_inactive_run() {
        val settled =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.COMPLETED, status = "succeeded", eventId = "settled"))
        val settledInterrupted = settled.interrupted()

        assertEquals(settled, settledInterrupted)

        val inactiveRun =
            RunObservationState(run = Run(run.id, run.sessionId, "succeeded"), state = RunPresentationState.RUNNING)
        val inactiveInterrupted = inactiveRun.interrupted()

        assertEquals(inactiveRun, inactiveInterrupted)
    }

    private fun event(
        type: RunEventType,
        status: String,
        text: String? = null,
        eventId: String,
    ): RunEvent =
        RunEvent(
            type = type,
            runId = run.id,
            status = status,
            text = text,
            eventId = eventId,
        )
}
