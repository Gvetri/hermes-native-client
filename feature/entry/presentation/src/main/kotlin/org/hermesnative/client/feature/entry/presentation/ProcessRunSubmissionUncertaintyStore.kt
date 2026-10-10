package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore

internal const val LEGACY_ATTEMPT_ID = "legacy"

internal sealed class UncertaintyStoreState : RunSubmissionUncertaintyStore {
    protected val lock = Any()
    protected val keys = mutableMapOf<PendingRunSubmissionKey, UncertaintyRecord>()

    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        synchronized(lock) {
            val current = keys[key]
            if (current != null && attemptId != null && current.attemptId != attemptId) return false
            val record = current ?: UncertaintyRecord(attemptId = attemptId ?: LEGACY_ATTEMPT_ID)
            record.knownRunIds += knownRunIds
            keys[key] = record
            return true
        }
    }

    override fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key] ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            keys.remove(key)
            true
        }

    override fun contains(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { key in keys }

    override fun clearEndpoint(endpoint: String) {
        synchronized(lock) {
            keys.keys.filter { it.endpoint == endpoint }.forEach(keys::remove)
        }
    }

    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> {
        return synchronized(lock) { keys[key]?.knownRunIds?.toSet().orEmpty() }
    }

    override fun attemptId(key: PendingRunSubmissionKey): String? = synchronized(lock) { keys[key]?.attemptId }

    override fun snapshot(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? =
        synchronized(lock) {
            keys[key]?.let { record ->
                RunSubmissionUncertaintySnapshot(
                    attemptId = record.attemptId,
                    knownRunIds = record.knownRunIds.toSet(),
                    boundRunId = record.boundRunId,
                    settled = record.settled,
                    requiresRunMatch = record.requiresRunMatch,
                )
            }
        }

    protected fun UncertaintyRecord.matchesAttempt(attemptId: String?): Boolean {
        return attemptId == null || this.attemptId == attemptId
    }
}

internal object ProcessRunSubmissionUncertaintyStore : UncertaintyStoreState() {
    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String?,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key] ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            current.boundRunId = runId
            true
        }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? = synchronized(lock) { keys[key]?.boundRunId }

    override fun markSettled(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key] ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            current.settled = true
            true
        }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { keys[key]?.settled == true }

    override fun markAmbiguous(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key] ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            current.requiresRunMatch = true
            true
        }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean {
        return synchronized(lock) { keys[key]?.requiresRunMatch == true }
    }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key]
            if (
                current == null ||
                current.knownRunIds.toSet() != knownRunIds ||
                !current.matchesAttempt(attemptId)
            ) {
                false
            } else {
                keys.remove(key)
                true
            }
        }

    private fun UncertaintyRecord.matchesSnapshot(snapshot: RunSubmissionUncertaintySnapshot): Boolean =
        attemptId == snapshot.attemptId &&
            knownRunIds.toSet() == snapshot.knownRunIds &&
            boundRunId == snapshot.boundRunId &&
            settled == snapshot.settled &&
            requiresRunMatch == snapshot.requiresRunMatch

    override fun removeIfSnapshotMatches(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ): Boolean =
        synchronized(lock) {
            val current = keys[key] ?: return@synchronized false
            if (current.matchesSnapshot(snapshot)) {
                keys.remove(key)
                true
            } else {
                false
            }
        }
}

internal data class UncertaintyRecord(
    val attemptId: String,
    val knownRunIds: MutableSet<RunId> = mutableSetOf(),
    var boundRunId: RunId? = null,
    var settled: Boolean = false,
    var requiresRunMatch: Boolean = false,
)
