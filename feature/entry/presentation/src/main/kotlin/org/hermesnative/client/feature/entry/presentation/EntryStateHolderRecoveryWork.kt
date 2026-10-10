package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.isActive
import org.hermesnative.client.feature.entry.domain.isValid

internal fun EntryStateHolder.claimRecoveryEntry(
    endpoint: String,
    entry: RunRecoveryEntry,
    expectedConnectionGeneration: Long,
): RecoveryClaim? {
    val key = RecoveryEntryKey(endpoint, entry.sessionId, entry.runId)
    val canClaim =
        connectionGeneration == expectedConnectionGeneration &&
            key !in recoveryHandledEntries &&
            key !in recoveryClaims
    if (!canClaim) return null
    return RecoveryClaim(
        key = key,
        connectionGeneration = expectedConnectionGeneration,
        claimId = ++nextRecoveryClaimId,
    ).also { claim -> recoveryClaims[key] = claim }
}

internal fun EntryStateHolder.releaseRecoveryClaim(claim: RecoveryClaim): Boolean {
    if (recoveryClaims[claim.key] != claim) return false
    recoveryClaims.remove(claim.key)
    return true
}

internal fun EntryStateHolder.startRunRecovery(expectedConnectionGeneration: Long) {
    val recovery = beginRunRecovery(expectedConnectionGeneration) ?: return
    val (registry, recoveryContext) = recovery
    if (!beginRecoveryLoad(expectedConnectionGeneration)) return
    val entries = loadRecoveryEntries(registry)
    when {
        entries == null ->
            finishRecoveryLoad(expectedConnectionGeneration, emptyList(), failed = true)
        entries.isEmpty() ->
            finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
        else -> {
            val workItems = claimRecoveryWorkItems(entries, recoveryContext, expectedConnectionGeneration)
            when {
                workItems == null ->
                    finishRecoveryLoad(expectedConnectionGeneration, emptyList(), failed = true)
                workItems.isEmpty() ->
                    finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
                else ->
                    startRecoveryWork(workItems, recoveryContext, expectedConnectionGeneration, entries)
            }
        }
    }
}

internal fun EntryStateHolder.beginRunRecovery(expectedConnectionGeneration: Long): RunRecoveryStart? {
    return runRecoveryRegistry?.let { registry ->
        recoveryGatewayContextFor(expectedConnectionGeneration)?.let { context -> registry to context }
    }
}

internal fun EntryStateHolder.recoveryGatewayContextFor(expectedConnectionGeneration: Long): RecoveryGatewayContext? =
    synchronized(sessionRequestLock) {
        if (connectionGeneration != expectedConnectionGeneration) {
            null
        } else {
            val sessionGateway = sessionGateway
            val runGateway = runGateway
            if (sessionGateway == null || runGateway == null) {
                null
            } else {
                RecoveryGatewayContext(
                    endpoint = mutableUiState.value.endpoint,
                    sessionGateway = sessionGateway,
                    runGateway = runGateway,
                )
            }
        }
    }

internal fun EntryStateHolder.loadRecoveryEntries(registry: RunRecoveryRegistry): List<RunRecoveryEntry>? =
    try {
        registry.load().filter(RunRecoveryEntry::isValid)
    } catch (_: Exception) {
        null
    }

internal fun EntryStateHolder.claimRecoveryWorkItems(
    entries: List<RunRecoveryEntry>,
    recoveryContext: RecoveryGatewayContext,
    expectedConnectionGeneration: Long,
): List<RecoveryWorkItem>? =
    synchronized(sessionRequestLock) {
        if (connectionGeneration != expectedConnectionGeneration) {
            null
        } else {
            entries.mapNotNull { entry ->
                val claim =
                    claimRecoveryEntry(recoveryContext.endpoint, entry, expectedConnectionGeneration)
                        ?: return@mapNotNull null
                rememberRecoveredRun(
                    entry = entry,
                    run = Run(entry.runId, entry.sessionId, RECOVERY_PENDING_STATUS),
                    updateVisibleState = false,
                )
                recoverySessionCounts[entry.sessionId] =
                    (recoverySessionCounts[entry.sessionId] ?: 0) + 1
                RecoveryWorkItem(entry, claim)
            }
        }
    }

internal fun EntryStateHolder.startRecoveryWork(
    workItems: List<RecoveryWorkItem>,
    recoveryContext: RecoveryGatewayContext,
    expectedConnectionGeneration: Long,
    entries: List<RunRecoveryEntry>,
) {
    lateinit var job: Job
    var jobToCancel: Job? = null
    synchronized(sessionRequestLock) {
        jobToCancel = recoveryJob
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    workItems.forEach { workItem ->
                        runRecoveryWorkItem(workItem, recoveryContext, expectedConnectionGeneration)
                    }
                } finally {
                    releaseRecoveryWork(workItems, expectedConnectionGeneration, job)
                }
            }
        recoveryJob = job
    }
    finishRecoveryLoad(expectedConnectionGeneration, entries, failed = false)
    jobToCancel?.cancel()
    job.start()
}

internal suspend fun EntryStateHolder.runRecoveryWorkItem(
    workItem: RecoveryWorkItem,
    recoveryContext: RecoveryGatewayContext,
    expectedConnectionGeneration: Long,
) {
    val entry = workItem.entry
    currentCoroutineContext().ensureActive()
    val expectedHistoryGeneration =
        synchronized(sessionRequestLock) {
            authoritativeSessionHistoryGenerations[entry.sessionId] ?: 0L
        }
    val recoveredRun =
        recoverRun(
            entry = entry,
            context =
                RecoveryRunContext(
                    sessionGateway = recoveryContext.sessionGateway,
                    runGateway = recoveryContext.runGateway,
                    connectionGeneration = expectedConnectionGeneration,
                    historyGeneration = expectedHistoryGeneration,
                ),
        )
    synchronized(sessionRequestLock) {
        if (connectionGeneration == expectedConnectionGeneration) {
            recoveryHandledEntries +=
                RecoveryEntryKey(recoveryContext.endpoint, entry.sessionId, entry.runId)
        }
    }
    if (recoveredRun == null) {
        markRecoveryFailure(entry.sessionId)
    } else {
        recoveredRun.takeIf(Run::isActive)?.let { run ->
            startRunObservation(
                sessionId = entry.sessionId,
                run = run,
                expectedConnectionGeneration = expectedConnectionGeneration,
                expectedHistoryGeneration = expectedHistoryGeneration,
            )
        }
    }
}

internal fun EntryStateHolder.releaseRecoveryWork(
    workItems: List<RecoveryWorkItem>,
    expectedConnectionGeneration: Long,
    job: Job,
) {
    synchronized(sessionRequestLock) {
        if (connectionGeneration == expectedConnectionGeneration) {
            workItems.forEach { workItem ->
                if (releaseRecoveryClaim(workItem.claim)) {
                    val entry = workItem.entry
                    val remaining = (recoverySessionCounts[entry.sessionId] ?: 1) - 1
                    if (remaining > 0) {
                        recoverySessionCounts[entry.sessionId] = remaining
                    } else {
                        recoverySessionCounts.remove(entry.sessionId)
                    }
                    clearRecoveryUiIfIdle(entry.sessionId)
                }
            }
        }
        if (recoveryJob === job) recoveryJob = null
    }
}
