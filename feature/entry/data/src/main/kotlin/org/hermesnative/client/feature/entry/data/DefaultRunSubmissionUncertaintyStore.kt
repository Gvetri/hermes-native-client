package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore

interface RunSubmissionUncertaintyStorage {
    fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot?

    fun write(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    )

    fun remove(key: PendingRunSubmissionKey)
}

class DefaultRunSubmissionUncertaintyStore(
    private val storage: RunSubmissionUncertaintyStorage,
) : RunSubmissionUncertaintyStore {
    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        synchronized(lock) {
            val current = storage.read(key)
            if (current != null && attemptId != null && current.attemptId != attemptId) return false
            val next =
                (current ?: RunSubmissionUncertaintySnapshot(attemptId ?: LEGACY_ATTEMPT_ID, emptySet(), null, false, false)).copy(
                    knownRunIds = current?.knownRunIds.orEmpty() + knownRunIds,
                )
            storage.write(key, next)
            return true
        }
    }

    override fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean {
        synchronized(lock) {
            val current = storage.read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            storage.remove(key)
            return true
        }
    }

    override fun contains(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { storage.read(key) != null }

    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> = synchronized(lock) { storage.read(key)?.knownRunIds.orEmpty() }

    override fun attemptId(key: PendingRunSubmissionKey): String? = synchronized(lock) { storage.read(key)?.attemptId }

    override fun snapshot(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? = synchronized(lock) { storage.read(key) }

    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(boundRunId = runId) }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? = synchronized(lock) { storage.read(key)?.boundRunId }

    override fun markSettled(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(settled = true) }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { storage.read(key)?.settled == true }

    override fun markAmbiguous(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(requiresRunMatch = true) }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean =
        synchronized(lock) { storage.read(key)?.requiresRunMatch == true }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        synchronized(lock) {
            val current = storage.read(key) ?: return false
            if ((attemptId != null && current.attemptId != attemptId) || current.knownRunIds != knownRunIds) return false
            storage.remove(key)
            return true
        }
    }

    override fun removeIfSnapshotMatches(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ): Boolean {
        synchronized(lock) {
            if (storage.read(key) != snapshot) return false
            storage.remove(key)
            return true
        }
    }

    private fun updateIfMatching(
        key: PendingRunSubmissionKey,
        attemptId: String?,
        transform: (RunSubmissionUncertaintySnapshot) -> RunSubmissionUncertaintySnapshot,
    ): Boolean {
        synchronized(lock) {
            val current = storage.read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            storage.write(key, transform(current))
            return true
        }
    }

    private companion object {
        const val LEGACY_ATTEMPT_ID = "legacy"
        val lock = Any()
    }
}
