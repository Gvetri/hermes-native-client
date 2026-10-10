package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyReader
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore

private const val LEGACY_ATTEMPT_ID = "legacy"

private val submissionUncertaintyLock = Any()

interface RunSubmissionUncertaintyStorage {
    fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot?

    fun write(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    )

    fun remove(key: PendingRunSubmissionKey)

    fun clearEndpoint(endpoint: String) = Unit
}

class DefaultRunSubmissionUncertaintyStore(
    private val storage: RunSubmissionUncertaintyStorage,
) : RunSubmissionUncertaintyStore,
    RunSubmissionUncertaintyReader by RunSubmissionUncertaintyReads(storage) {
    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        synchronized(submissionUncertaintyLock) {
            val current = storage.read(key)
            if (current != null && attemptId != null && current.attemptId != attemptId) return false
            val base =
                current
                    ?: RunSubmissionUncertaintySnapshot(attemptId ?: LEGACY_ATTEMPT_ID, emptySet(), null, false, false)
            val next =
                base.copy(
                    knownRunIds = current?.knownRunIds.orEmpty() + knownRunIds,
                )
            storage.write(key, next)
            return true
        }
    }

    override fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean =
        synchronized(submissionUncertaintyLock) {
            val current = storage.read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            storage.remove(key)
            true
        }

    override fun contains(key: PendingRunSubmissionKey): Boolean {
        return synchronized(submissionUncertaintyLock) { storage.read(key) != null }
    }

    override fun clearEndpoint(endpoint: String) {
        synchronized(submissionUncertaintyLock) {
            storage.clearEndpoint(endpoint)
        }
    }

    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(boundRunId = runId) }

    override fun markSettled(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(settled = true) }

    override fun markAmbiguous(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(requiresRunMatch = true) }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean =
        synchronized(submissionUncertaintyLock) {
            val current = storage.read(key) ?: return false
            if (
                (attemptId != null && current.attemptId != attemptId) ||
                current.knownRunIds != knownRunIds
            ) {
                return false
            }
            storage.remove(key)
            true
        }

    override fun removeIfSnapshotMatches(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ): Boolean {
        synchronized(submissionUncertaintyLock) {
            if (storage.read(key) != snapshot) return false
            storage.remove(key)
            return true
        }
    }

    private fun updateIfMatching(
        key: PendingRunSubmissionKey,
        attemptId: String?,
        transform: (RunSubmissionUncertaintySnapshot) -> RunSubmissionUncertaintySnapshot,
    ): Boolean =
        synchronized(submissionUncertaintyLock) {
            val current = storage.read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            storage.write(key, transform(current))
            true
        }
}

private class RunSubmissionUncertaintyReads(
    private val storage: RunSubmissionUncertaintyStorage,
) : RunSubmissionUncertaintyReader {
    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> {
        return synchronized(submissionUncertaintyLock) { storage.read(key)?.knownRunIds.orEmpty() }
    }

    override fun attemptId(key: PendingRunSubmissionKey): String? {
        return synchronized(submissionUncertaintyLock) { storage.read(key)?.attemptId }
    }

    override fun snapshot(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? {
        return synchronized(submissionUncertaintyLock) { storage.read(key) }
    }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? =
        synchronized(submissionUncertaintyLock) { storage.read(key)?.boundRunId }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean {
        return synchronized(submissionUncertaintyLock) { storage.read(key)?.settled == true }
    }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean =
        synchronized(submissionUncertaintyLock) { storage.read(key)?.requiresRunMatch == true }
}
