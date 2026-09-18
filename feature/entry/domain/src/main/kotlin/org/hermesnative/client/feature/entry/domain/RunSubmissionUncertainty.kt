package org.hermesnative.client.feature.entry.domain

data class PendingRunSubmissionKey(
    val endpoint: String,
    val sessionId: SessionId,
)

data class RunSubmissionUncertaintySnapshot(
    val attemptId: String,
    val knownRunIds: Set<RunId>,
    val boundRunId: RunId?,
    val settled: Boolean,
    val requiresRunMatch: Boolean,
)

interface RunSubmissionUncertaintyStore {
    fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId> = emptySet(),
        attemptId: String? = null,
    ): Boolean

    fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String? = null,
    ): Boolean

    fun contains(key: PendingRunSubmissionKey): Boolean

    fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> = emptySet()

    fun attemptId(key: PendingRunSubmissionKey): String? = null

    fun snapshot(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? = null

    fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String? = null,
    ): Boolean = true

    fun boundRunId(key: PendingRunSubmissionKey): RunId? = null

    fun markSettled(
        key: PendingRunSubmissionKey,
        attemptId: String? = null,
    ): Boolean = true

    fun isSettled(key: PendingRunSubmissionKey): Boolean = false

    fun markAmbiguous(
        key: PendingRunSubmissionKey,
        attemptId: String? = null,
    ): Boolean = true

    fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean = false

    fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String? = null,
    ): Boolean = false

    fun removeIfSnapshotMatches(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ): Boolean = false
}
