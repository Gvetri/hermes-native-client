package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal
import org.hermesnative.client.feature.entry.domain.runs
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.updateVisibleRunState(sessionId: SessionId) {
    val current = mutableUiState.value.sessionList ?: return
    val opened = current.openedSession?.takeIf { it.session.id == sessionId } ?: return
    val knownRuns = visibleSessionRuns(sessionId)
    val latestObservation = latestObservationState(sessionId, knownRuns)
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.copy(
                            latestRun = knownRuns.latestRun(),
                            activeRuns = knownRuns.activeRuns(),
                            latestRunState =
                                latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                            latestRunRetryAvailable =
                                latestRunRetryAvailable(
                                    knownRuns,
                                    latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                ),
                            activeResponse = observedMessageUiState(latestObservation),
                        ),
                ),
        )
}

internal fun EntryStateHolder.forgetObservationState(
    sessionId: SessionId,
    runId: RunId,
) {
    val states = runObservationStates[sessionId] ?: return
    states.remove(runId)
    if (states.isEmpty()) {
        runObservationStates.remove(sessionId)
    }
}

internal fun EntryStateHolder.latestObservationState(
    sessionId: SessionId,
    runs: List<Run>,
): RunObservationState? {
    val states = runObservationStates[sessionId] ?: return null
    return runs.latestRun()?.let { states[it.id] }
}

internal fun EntryStateHolder.latestObservedObservationState(
    sessionId: SessionId,
    runs: List<Run>,
): RunObservationState? {
    val states = runObservationStates[sessionId] ?: return null
    return runs.asReversed().firstOrNull { states.containsKey(it.id) }?.let { states[it.id] }
}

internal fun EntryStateHolder.forgetConfirmedObservationStates(
    sessionId: SessionId,
    confirmedRunIds: Set<RunId>,
) {
    if (confirmedRunIds.isEmpty()) return
    val states = runObservationStates[sessionId] ?: return
    states.keys.removeAll(confirmedRunIds)
    if (states.isEmpty()) {
        runObservationStates.remove(sessionId)
    }
}

internal fun EntryStateHolder.retainUnconfirmedTerminalRuns(sessionId: SessionId) {
    val states = runObservationStates[sessionId].orEmpty().values.toList()
    val terminalStates = states.filter { state -> state.state.isTerminal() || !state.run.isActive() }
    val locallyOwnedTerminalStates = terminalStates.filter { state -> isLocallyOwnedRun(sessionId, state.run.id) }
    terminalStates
        .filterNot { state -> state.run.id in locallyOwnedTerminalStates.mapTo(mutableSetOf()) { it.run.id } }
        .forEach { state -> forgetObservationState(sessionId, state.run.id) }
    if (locallyOwnedTerminalStates.isEmpty()) return
    locallyOwnedTerminalStates.forEach { state ->
        rememberObservationState(uncertainObservationState(state.run, state))
    }
    sessionRuns[sessionId] =
        mergeRuns(sessionRuns[sessionId].orEmpty(), locallyOwnedTerminalStates.map(RunObservationState::run))
}

internal fun EntryStateHolder.allObservationStates(sessionId: SessionId): List<RunObservationState> =
    runObservationStates[sessionId]?.values?.toList().orEmpty()

internal fun EntryStateHolder.visibleSessionRuns(sessionId: SessionId): List<Run> {
    val runs =
        mergeRuns(
            authoritativeSessionRuns[sessionId].orEmpty(),
            sessionRuns[sessionId].orEmpty(),
        )
    val unresolvedRunIds =
        buildSet {
            uncertainSubmissionRunIds[sessionId]?.let(::add)
            unresolvedLocalRunIds[recoverySessionKey(sessionId)].orEmpty().forEach(::add)
        }
    return runs.filter { it.id !in unresolvedRunIds } + runs.filter { it.id in unresolvedRunIds }
}

internal fun EntryStateHolder.isLocallyOwnedRun(
    sessionId: SessionId,
    runId: RunId,
): Boolean =
    sessionRuns[sessionId].orEmpty().any { it.id == runId } ||
        uncertainSubmissionRunIds[sessionId] == runId ||
        unresolvedLocalRunIds[recoverySessionKey(sessionId)]?.contains(runId) == true
