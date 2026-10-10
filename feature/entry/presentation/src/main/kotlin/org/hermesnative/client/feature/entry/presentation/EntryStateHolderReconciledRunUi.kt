package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunObservationState
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.toRunPresentationState

internal fun EntryStateHolder.updateUnconfirmedRunSessionUi(
    sessionId: SessionId,
    run: Run,
    session: Pair<SessionListUiState, OpenSessionUiState>?,
) {
    val previous = observationStateFor(sessionId, run.id)
    val uncertainState = uncertainObservationState(run, previous)
    rememberObservationState(uncertainState)
    if (session == null) return
    val (current, opened) = session
    val knownRuns = visibleSessionRuns(sessionId)
    val latestRun = knownRuns.latestRun()
    val latestObservation = latestObservationState(sessionId, knownRuns)
    mutableUiState.value =
        mutableUiState.value.copy(
            sessionList =
                current.copy(
                    openedSession =
                        opened.copy(
                            latestRun = latestRun,
                            activeRuns = knownRuns.activeRuns(),
                            sendErrorCategory = opened.sendErrorCategory,
                            hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                            latestRunState =
                                latestObservation?.state ?: latestRun?.toRunPresentationState(),
                            latestRunRetryAvailable =
                                latestRunRetryAvailable(
                                    knownRuns,
                                    latestObservation?.state ?: latestRun?.toRunPresentationState(),
                                ),
                            activeResponse = observedMessageUiState(latestObservation),
                            errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                            isStale = true,
                        ),
                ),
        )
}

internal fun EntryStateHolder.removeReconciledRecoveryEntry(
    recoveryEntryToRemove: RunRecoveryEntry?,
    recoveryEndpointToRemove: String?,
    sessionId: SessionId,
    requestConnectionGeneration: Long,
    requestHistoryGeneration: Long?,
) {
    var shouldRemoveRecoveryEntry = false
    recoveryEntryToRemove?.let { entry ->
        synchronized(sessionRequestLock) {
            val endpoint = recoveryEndpointToRemove
            val historyConfirmsTerminal = historyConfirmsTerminalRun(entry.sessionId, entry.runId)
            val endpointMatches = endpoint == null || mutableUiState.value.endpoint == endpoint
            val historyMatches =
                requestHistoryGeneration == null ||
                    (authoritativeSessionHistoryGenerations[sessionId] ?: 0L) == requestHistoryGeneration ||
                    historyConfirmsTerminal
            shouldRemoveRecoveryEntry =
                connectionGeneration == requestConnectionGeneration && endpointMatches && historyMatches
        }
    }
    if (shouldRemoveRecoveryEntry) {
        removeRecoveryEntry(
            endpoint = recoveryEndpointToRemove,
            entry = requireNotNull(recoveryEntryToRemove),
        )
    }
}

internal fun EntryStateHolder.showRunReconciliationFailure(
    sessionId: SessionId,
    runId: RunId,
    requestConnectionGeneration: Long,
    requestSessionGeneration: Long,
) {
    synchronized(sessionRequestLock) {
        if (
            connectionGeneration != requestConnectionGeneration ||
            sessionRequestGeneration != requestSessionGeneration
        ) {
            return@synchronized
        }
        val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
        val knownRun =
            visibleSessionRuns(sessionId).lastOrNull { it.id == runId }
                ?: Run(runId, sessionId, UNCERTAIN_RUN_STATUS)
        val uncertainRun = knownRun
        if (isLocallyOwnedRun(sessionId, runId)) {
            sessionRuns[sessionId] = mergeRuns(sessionRuns[sessionId].orEmpty(), listOf(uncertainRun))
        } else {
            authoritativeSessionRuns[sessionId] =
                mergeRuns(authoritativeSessionRuns[sessionId].orEmpty(), listOf(uncertainRun))
        }
        val knownRuns = visibleSessionRuns(sessionId)
        val previous = observationStateFor(sessionId, runId)
        val uncertainState = uncertainObservationState(uncertainRun, previous)
        rememberObservationState(uncertainState)
        val latestObservation = latestObservationState(sessionId, knownRuns)
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                latestRun = knownRuns.latestRun(),
                                activeRuns = knownRuns.activeRuns(),
                                sendErrorCategory = opened.sendErrorCategory,
                                hasUnresolvedSubmission = hasUnresolvedSubmission(sessionId),
                                latestRunState =
                                    latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                latestRunRetryAvailable =
                                    latestRunRetryAvailable(
                                        knownRuns,
                                        latestObservation?.state ?: knownRuns.latestRun()?.toRunPresentationState(),
                                    ),
                                activeResponse = observedMessageUiState(latestObservation),
                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                isStale = true,
                            ),
                    ),
            )
    }
}

internal fun EntryStateHolder.uncertainObservationState(
    run: Run,
    previous: RunObservationState?,
): RunObservationState =
    (previous ?: RunObservationState(run = run, state = RunPresentationState.UNCERTAIN)).copy(
        run = run,
        state = RunPresentationState.UNCERTAIN,
        isStreaming = false,
    )

internal fun EntryStateHolder.hasUnresolvedSubmission(sessionId: SessionId): Boolean {
    val hasUncertainRun = uncertainSubmissionRunIds.containsKey(sessionId)
    val uncertainRunId =
        if (hasUncertainRun) {
            uncertainSubmissionRunIds[sessionId]
        } else {
            null
        }
    val uncertainRun =
        uncertainRunId?.let { runId ->
            visibleSessionRuns(sessionId).lastOrNull { it.id == runId }
        }
    return unresolvedSubmissionSessions.contains(sessionId) ||
        recoveryUnavailableSessions.contains(sessionId) ||
        pendingCreateSessions.contains(recoverySessionKey(sessionId)) ||
        runSubmissionUncertaintyStore.contains(pendingRunSubmissionKey(sessionId)) ||
        !unresolvedLocalRunIds[recoverySessionKey(sessionId)].isNullOrEmpty() ||
        (
            uncertainRunId != null &&
                (
                    sessionSendErrors[sessionId] == MessageSendErrorCategory.UNCERTAIN ||
                        uncertainRun == null ||
                        !uncertainRun.isActive()
                )
        )
}

internal fun EntryStateHolder.sendErrorCategoryFor(sessionId: SessionId): MessageSendErrorCategory? =
    sessionSendErrors[sessionId]
        ?: MessageSendErrorCategory.UNCERTAIN.takeIf { recoveryUnavailableSessions.contains(sessionId) }
        ?: MessageSendErrorCategory.UNCERTAIN.takeIf { hasUnresolvedSubmission(sessionId) }
