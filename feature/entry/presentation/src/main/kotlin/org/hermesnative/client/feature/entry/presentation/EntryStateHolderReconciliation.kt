package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import org.hermesnative.client.feature.entry.application.ReconcileRun
import org.hermesnative.client.feature.entry.domain.AuthoritativeRunReconciliation
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isTerminal

internal fun EntryStateHolder.reconcileOpenedRun(context: OpenedRunReconcileContext) {
    val recoveryEntries =
        try {
            runRecoveryRegistry?.load().orEmpty()
        } catch (_: Exception) {
            null
        }
    val recoveryEntriesLoadFailed = recoveryEntries == null
    val sessionRecoveryEntries =
        recoveryEntries.orEmpty().filter { entry -> entry.sessionId == context.sessionId }
    val runIds = reconciliationRunIds(context, sessionRecoveryEntries, recoveryEntriesLoadFailed) ?: return
    if (runIds.isEmpty()) {
        if (context.clearRefreshWhenNoRun) {
            finishReconciliation(
                context.sessionId,
                context.requestConnectionGeneration,
                context.requestSessionGeneration,
            )
        }
        if (recoveryLoadFailed) {
            markRecoveryUnavailable(
                context.sessionId,
                context.requestConnectionGeneration,
                context.requestSessionGeneration,
            )
        }
        return
    }
    var activeRunToObserve: Run? = null
    runIds.forEach { runId ->
        reconcileRunObservationCandidate(context, runId, activeRunToObserve != null)
            ?.let { candidate -> activeRunToObserve = candidate }
    }
    val runToObserve = activeRunToObserve
    if (
        context.restartObservation &&
        runToObserve != null &&
        shouldReconcileCurrentSession(
            context.requestConnectionGeneration,
            context.requestSessionGeneration,
            context.sessionId,
        )
    ) {
        startRunObservation(
            sessionId = context.sessionId,
            run = runToObserve,
            expectedConnectionGeneration = context.requestConnectionGeneration,
            expectedSessionGeneration = context.requestSessionGeneration,
        )
    }
    if (recoveryLoadFailed) {
        markRecoveryUnavailable(
            context.sessionId,
            context.requestConnectionGeneration,
            context.requestSessionGeneration,
        )
    }
}

internal fun EntryStateHolder.reconciliationRunIds(
    context: OpenedRunReconcileContext,
    sessionRecoveryEntries: List<RunRecoveryEntry>,
    recoveryEntriesLoadFailed: Boolean,
): List<RunId>? =
    synchronized(sessionRequestLock) {
        if (
            connectionGeneration != context.requestConnectionGeneration ||
            sessionRequestGeneration != context.requestSessionGeneration ||
            sessionGateway !== context.sessionGateway
        ) {
            null
        } else {
            val ids = linkedSetOf<RunId>()
            context.runIdToReconcile?.let(ids::add)
            val pendingKey = PendingRunSubmissionKey(mutableUiState.value.endpoint, context.sessionId)
            runSubmissionUncertaintyStore
                .boundRunId(pendingKey)
                ?.let { boundRunId ->
                    uncertainSubmissionRunIds[context.sessionId] = boundRunId
                    uncertainSubmissionAttemptIds[context.sessionId] =
                        runSubmissionUncertaintyStore.attemptId(pendingKey) ?: LEGACY_ATTEMPT_ID
                }
            recoveryLoadPending = false
            recoveryLoadFailed = recoveryEntriesLoadFailed
            if (recoveryEntriesLoadFailed) {
                recoveryUnavailableSessions.add(context.sessionId)
            } else {
                recoveryUnavailableSessions.remove(context.sessionId)
            }
            sessionRecoveryEntries.forEach { entry ->
                ids += entry.runId
                if (
                    sessionRuns[context.sessionId].orEmpty().none { it.id == entry.runId } &&
                    observationStateFor(context.sessionId, entry.runId) == null
                ) {
                    rememberRecoveredRun(
                        entry = entry,
                        run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                        updateVisibleState = false,
                    )
                }
            }
            sessionRuns[context.sessionId].orEmpty().filter(Run::isActive).mapTo(ids) { it.id }
            unresolvedLocalRunIds[recoverySessionKey(context.sessionId)].orEmpty().forEach(ids::add)
            uncertainSubmissionRunIds[context.sessionId]?.let(ids::add)
            visibleSessionRuns(context.sessionId).latestActiveRun()?.id?.let(ids::add)
            if (ids.isEmpty()) {
                latestObservedObservationState(context.sessionId, visibleSessionRuns(context.sessionId))
                    ?.takeIf { state -> !state.state.isTerminal() }
                    ?.run
                    ?.id
                    ?.let(ids::add)
            }
            ids.toList()
        }
    }

internal fun EntryStateHolder.reconcileRunObservationCandidate(
    context: OpenedRunReconcileContext,
    runId: RunId,
    hasCandidate: Boolean,
): Run? {
    val run =
        synchronized(sessionRequestLock) {
            visibleSessionRuns(context.sessionId).lastOrNull { it.id == runId }
                ?: observationStateFor(context.sessionId, runId)?.run
                ?: unresolvedLocalRunIds[recoverySessionKey(context.sessionId)]
                    ?.takeIf { runId in it }
                    ?.let { Run(runId, context.sessionId, UNCERTAIN_RUN_STATUS) }
                ?: uncertainSubmissionRunIds[context.sessionId]
                    ?.takeIf { runId == it }
                    ?.let { Run(runId, context.sessionId, UNCERTAIN_RUN_STATUS) }
        } ?: return null
    val reconciliation =
        reconcileRun(
            sessionId = context.sessionId,
            runId = run.id,
            requestConnectionGeneration = context.requestConnectionGeneration,
            requestSessionGeneration = context.requestSessionGeneration,
            sessionGateway = context.sessionGateway,
        )
    val reconciledRun = reconciliation?.run
    return continuedObservationRun(reconciledRun, run, hasCandidate)
}

internal fun EntryStateHolder.continuedObservationRun(
    reconciledRun: Run?,
    fallback: Run,
    hasCandidate: Boolean,
): Run? {
    val reconciledIsActive = reconciledRun?.isActive() == true
    val fallbackIsActive = reconciledRun == null && !hasCandidate && fallback.isActive()
    return if (reconciledIsActive || fallbackIsActive) reconciledRun ?: fallback else null
}

internal fun EntryStateHolder.markRecoveryUnavailable(
    sessionId: SessionId,
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
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isRefreshing = false,
                                isStale = true,
                                errorCategory = SessionHistoryErrorCategory.RECONCILIATION_FAILED,
                                sendErrorCategory = MessageSendErrorCategory.UNCERTAIN,
                                hasUnresolvedSubmission = true,
                                latestRunState = opened.latestRunState ?: RunPresentationState.UNCERTAIN,
                                isReconciliationInProgress = false,
                            ),
                    ),
            )
    }
}

internal fun EntryStateHolder.reconcileRun(
    sessionId: SessionId,
    runId: RunId,
    requestConnectionGeneration: Long,
    requestSessionGeneration: Long,
    sessionGateway: SessionGatewayPort,
): AuthoritativeRunReconciliation? {
    val runGateway =
        if (beginReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)) {
            synchronized(sessionRequestLock) {
                runGateway?.takeIf {
                    connectionGeneration == requestConnectionGeneration &&
                        sessionRequestGeneration == requestSessionGeneration &&
                        mutableUiState.value.sessionList?.openedSession?.session?.id == sessionId
                }
            }
        } else {
            null
        } ?: return null
    return try {
        val result = ReconcileRun(runGateway, sessionGateway).execute(runId, sessionId)
        applyAuthoritativeRunReconciliation(
            request =
                AuthoritativeReconciliationRequest(
                    sessionId = sessionId,
                    connectionGeneration = requestConnectionGeneration,
                    sessionGeneration = requestSessionGeneration,
                ),
            reconciliation = result,
        )
        result
    } catch (error: CancellationException) {
        throw error
    } catch (_: GatewayException) {
        showRunReconciliationFailure(sessionId, runId, requestConnectionGeneration, requestSessionGeneration)
        null
    } catch (_: Exception) {
        showRunReconciliationFailure(sessionId, runId, requestConnectionGeneration, requestSessionGeneration)
        null
    } finally {
        finishReconciliation(sessionId, requestConnectionGeneration, requestSessionGeneration)
    }
}

internal fun EntryStateHolder.beginReconciliation(
    sessionId: SessionId,
    requestConnectionGeneration: Long,
    requestSessionGeneration: Long,
): Boolean =
    synchronized(sessionRequestLock) {
        val opened = mutableUiState.value.sessionList?.openedSession
        val reconciliationIsSuperseded =
            connectionGeneration != requestConnectionGeneration ||
                sessionRequestGeneration != requestSessionGeneration ||
                opened?.session?.id != sessionId ||
                reconcilingSessions[sessionId] == requestSessionGeneration
        if (reconciliationIsSuperseded) {
            false
        } else {
            reconcilingSessions[sessionId] = requestSessionGeneration
            mutableUiState.value =
                mutableUiState.value.copy(
                    sessionList =
                        mutableUiState.value.sessionList?.copy(
                            openedSession =
                                opened.copy(
                                    isRefreshing = true,
                                    isReconciliationInProgress = true,
                                    errorCategory = null,
                                ),
                        ),
                )
            true
        }
    }

internal fun EntryStateHolder.finishReconciliation(
    sessionId: SessionId,
    requestConnectionGeneration: Long,
    requestSessionGeneration: Long,
) {
    synchronized(sessionRequestLock) {
        if (reconcilingSessions[sessionId] == requestSessionGeneration) {
            reconcilingSessions.remove(sessionId)
        }
        if (
            connectionGeneration != requestConnectionGeneration ||
            sessionRequestGeneration != requestSessionGeneration
        ) {
            return@synchronized
        }
        val (current, opened) = openedSessionFor(sessionId) ?: return@synchronized
        mutableUiState.value =
            mutableUiState.value.copy(
                sessionList =
                    current.copy(
                        openedSession =
                            opened.copy(
                                isRefreshing = false,
                                isReconciliationInProgress = false,
                            ),
                    ),
            )
    }
}

internal fun EntryStateHolder.applyAuthoritativeRunReconciliation(
    request: AuthoritativeReconciliationRequest,
    reconciliation: AuthoritativeRunReconciliation,
) {
    val application = applyReconciliationLocked(request, reconciliation)
    application.observationToClose?.close()
    application.observationJobToCancel?.cancel()
    removeReconciledRecoveryEntry(
        recoveryEntryToRemove = application.recoveryEntryToRemove,
        recoveryEndpointToRemove = application.recoveryEndpointToRemove,
        sessionId = request.sessionId,
        requestConnectionGeneration = request.connectionGeneration,
        requestHistoryGeneration = request.historyGeneration,
    )
}
