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
    fun an_interruption_does_not_confirm_that_the_remote_run_is_terminal() {
        val state =
            RunEventStateTransition.initial(run)
                .transition(event(RunEventType.INTERRUPTED, status = "interrupted", eventId = "interrupted"))

        assertEquals(RunPresentationState.UNCERTAIN, state.state)
        assertTrue(state.run.isActive())
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
