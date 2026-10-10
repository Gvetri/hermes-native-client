package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey

internal fun EntryStateHolder.releaseConnectionState(): ReleasedConnectionState =
    synchronized(connectionPersistenceLock) {
        synchronized(sessionRequestLock) {
            sessionRequestGeneration += 1
            connectionGeneration += 1
            sessionGateway = null
            runGateway = null
            val supersededJobs = listOfNotNull(verificationJob, sessionJob, recoveryJob)
            verificationJob = null
            sessionJob = null
            recoveryJob = null
            val requestJobs =
                listOf(
                    mutationJobs.values,
                    runJobs.values,
                    runObservationJobs.values,
                    connectionRecoveryJobs,
                ).flatten()
            val observationsToClose = runObservations.values.toList()
            clearSessionStateForClose()
            ReleasedConnectionState(
                jobs = requestJobs + supersededJobs,
                observations = observationsToClose,
            )
        }
    }

internal fun EntryStateHolder.clearSessionStateForClose() {
    mutationJobs.clear()
    mutationOwners.clear()
    runJobs.clear()
    runObservationJobs.clear()
    runObservations.clear()
    runObservationRunIds.clear()
    pendingRunObservationRequests.clear()
    runObservationStates.clear()
    unresolvedSubmissionSessions.clear()
    ambiguousSubmissionSessions.clear()
    recoveryUnavailableSessions.clear()
    uncertainSendDrafts.clear()
    uncertainSubmissionRunIds.clear()
    uncertainSubmissionAttemptIds.clear()
    persistPendingCreates()
    pendingCreateSessions.clear()
    pendingCreateStarted.clear()
    unresolvedLocalRunIds.clear()
    unresolvedLocalRunAttempts.clear()
    pendingTimedOutSends.clear()
    reconcilingSessions.clear()
    notifiedTerminalRunIds.clear()
    connectionRecoveryJobs.clear()
    connectionRecoverySessionCounts.clear()
    connectionRecoveryFailedSessions.clear()
    recoverySessionCounts.clear()
    recoveryClaims.clear()
    recoveryHandledEntries.clear()
    recoveryLoadPending = false
    recoveryLoadFailed = false
    sessionDrafts.clear()
    sessionDraftRevisions.clear()
    sessionSendErrors.clear()
    pendingRunDrafts.clear()
    sessionRuns.clear()
    submittedRunInputs.clear()
    authoritativeSessionHistoryGenerations.clear()
    authoritativeSessionRuns.clear()
}

internal fun EntryStateHolder.persistPendingCreates() {
    pendingCreateSessions.forEach { (key, pendingCreate) ->
        val submissionKey = PendingRunSubmissionKey(key.endpoint, key.sessionId)
        val persisted =
            runCatching {
                runSubmissionUncertaintyStore.add(
                    submissionKey,
                    pendingCreate.knownRunIds,
                    pendingCreate.attemptId,
                )
            }.getOrDefault(false)
        if (persisted && key !in pendingCreateStarted) {
            runCatching {
                runSubmissionUncertaintyStore.markSettled(submissionKey, pendingCreate.attemptId)
            }
        }
    }
}

internal fun EntryStateHolder.showConnectionSetup() {
    if (mutableUiState.value.isConnected) return
    mutableUiState.value = mutableUiState.value.connectionSetupState()
}

internal fun EntryStateHolder.showCredentialRotation() {
    val state = mutableUiState.value
    if (!state.isConnected || state.isVerifying) return
    mutableUiState.value =
        state.copy(
            title = "Change Gateway credential",
            supportingText = "Verify the replacement before it replaces the saved credential.",
            actionLabel = "Save credential",
            connectionSetupRequested = true,
            isChangingCredential = true,
            bearerCredential = "",
            errorCategory = null,
        )
}

internal fun EntryStateHolder.cancelCredentialRotation() {
    val state = mutableUiState.value
    if (!state.isChangingCredential || state.isVerifying) return
    mutableUiState.value =
        state.copy(
            title = "Gateway connected",
            supportingText = "The Gateway contract was verified successfully.",
            actionLabel = "Connected",
            isChangingCredential = false,
            errorCategory = null,
        )
}

internal fun EntryStateHolder.updateEndpoint(value: String) {
    val state = mutableUiState.value
    if (state.isVerifying || state.isConnected) return
    mutableUiState.value = state.copy(endpoint = value, errorCategory = null)
}

internal fun EntryStateHolder.updateBearerCredential(value: String) {
    val state = mutableUiState.value
    if (state.isVerifying || (state.isConnected && !state.isChangingCredential)) return
    mutableUiState.value = state.copy(bearerCredential = value, errorCategory = null)
}

internal fun EntryStateHolder.updateSaveCredential(value: Boolean) {
    val state = mutableUiState.value
    if (state.isVerifying || (state.isConnected && !state.isChangingCredential)) return
    mutableUiState.value = state.copy(saveCredential = value, errorCategory = null)
}

internal fun EntryStateHolder.verifyConnection() {
    val verifier = verifyGatewayConnection ?: return
    val job =
        synchronized(connectionPersistenceLock) {
            synchronized(sessionRequestLock) {
                val state = mutableUiState.value
                if (state.blocksVerification()) {
                    null
                } else {
                    verificationJob?.cancel()
                    val requestConnectionGeneration = connectionGeneration
                    mutableUiState.value = state.copy(isVerifying = true, errorCategory = null)
                    val verification =
                        scope.launch(start = CoroutineStart.LAZY) {
                            runVerification(verifier, state, requestConnectionGeneration)
                        }
                    verificationJob = verification
                    verification
                }
            }
        } ?: return
    job.start()
}
