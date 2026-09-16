package org.hermesnative.client.feature.entry.domain

import java.util.Locale

/** Stable labels that the client may expose for a Gateway Run. */
enum class RunPresentationState(
    val label: String,
) {
    STARTING("Starting"),
    RUNNING("Running"),
    COMPLETING("Completing"),
    SUCCEEDED("Succeeded"),
    FAILED("Failed"),
    UNCERTAIN("Uncertain"),
}

/** Compatibility name for callers that describe this as the Run state. */
typealias RunState = RunPresentationState

fun String.toRunPresentationState(): RunPresentationState =
    when (trim().lowercase(Locale.ROOT)) {
        "queued", "starting", "started" -> RunPresentationState.STARTING
        "running", "in_progress", "in-progress" -> RunPresentationState.RUNNING
        "completing", "finalizing", "stopping" -> RunPresentationState.COMPLETING
        "completed", "complete", "succeeded", "success" -> RunPresentationState.SUCCEEDED
        "failed", "failure", "error" -> RunPresentationState.FAILED
        else -> RunPresentationState.UNCERTAIN
    }

fun Run.toRunPresentationState(): RunPresentationState = status.toRunPresentationState()

fun RunPresentationState.isTerminal(): Boolean = this == RunPresentationState.SUCCEEDED || this == RunPresentationState.FAILED

data class RunObservationState(
    val run: Run,
    val state: RunPresentationState = run.toRunPresentationState(),
    val responseText: String = "",
    val isStreaming: Boolean = false,
    val isStreamInterrupted: Boolean = false,
    val processedEventIds: Set<String> = emptySet(),
) {
    fun transition(event: RunEvent): RunObservationState = RunEventStateTransition.apply(this, event)

    fun interrupted(): RunObservationState = RunEventStateTransition.interrupted(this)

    val userFacingState: RunPresentationState
        get() = state
}

object RunEventStateTransition {
    fun initial(run: Run): RunObservationState =
        RunObservationState(
            run = run,
            isStreaming = run.isActive() && run.toRunPresentationState() != RunPresentationState.UNCERTAIN,
        )

    fun apply(
        current: RunObservationState,
        event: RunEvent,
    ): RunObservationState {
        if (event.runId != current.run.id) return current
        val eventId = event.eventId?.takeIf(String::isNotBlank)
        if (eventId != null && eventId in current.processedEventIds) return current

        val processedEventIds =
            if (eventId == null) {
                current.processedEventIds
            } else {
                current.processedEventIds + eventId
            }
        val status = event.status.takeIf(String::isNotBlank) ?: current.run.status
        val nextRun = current.run.copy(status = status)
        val nextState = event.presentationState()
        val nextText =
            if (event.type == RunEventType.MESSAGE_DELTA || event.type == RunEventType.TEXT_DELTA) {
                current.responseText + event.text.orEmpty()
            } else {
                current.responseText
            }
        val terminal = nextState == RunPresentationState.SUCCEEDED || nextState == RunPresentationState.FAILED
        return current.copy(
            run = nextRun,
            state = nextState,
            responseText = nextText,
            isStreaming = if (terminal || nextState == RunPresentationState.UNCERTAIN) false else current.isStreaming || event.text != null,
            isStreamInterrupted = event.type == RunEventType.INTERRUPTED,
            processedEventIds = processedEventIds,
        )
    }

    fun interrupted(current: RunObservationState): RunObservationState =
        if (current.state == RunPresentationState.SUCCEEDED || current.state == RunPresentationState.FAILED || !current.run.isActive()) {
            current
        } else {
            current.copy(
                state = RunPresentationState.UNCERTAIN,
                isStreaming = false,
                isStreamInterrupted = true,
            )
        }

    private fun RunEvent.presentationState(): RunPresentationState =
        when (type) {
            RunEventType.STARTED -> RunPresentationState.STARTING
            RunEventType.RUNNING,
            RunEventType.MESSAGE_DELTA,
            RunEventType.TEXT_DELTA,
            -> RunPresentationState.RUNNING
            RunEventType.COMPLETING -> RunPresentationState.COMPLETING
            RunEventType.SUCCEEDED -> RunPresentationState.SUCCEEDED
            RunEventType.FAILED -> RunPresentationState.FAILED
            RunEventType.INTERRUPTED -> RunPresentationState.UNCERTAIN
            RunEventType.COMPLETED ->
                when (status.trim().lowercase(Locale.ROOT)) {
                    "", "completed", "complete", "succeeded", "success" -> RunPresentationState.SUCCEEDED
                    "failed", "failure", "error" -> RunPresentationState.FAILED
                    else -> RunPresentationState.UNCERTAIN
                }
        }
}
