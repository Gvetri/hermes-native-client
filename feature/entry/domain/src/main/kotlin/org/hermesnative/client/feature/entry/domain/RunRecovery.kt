package org.hermesnative.client.feature.entry.domain

/** Minimum metadata needed to query a locally started Run after process restart. */
data class RunRecoveryEntry(
    val sessionId: SessionId,
    val runId: RunId,
)

fun RunRecoveryEntry.isValid(): Boolean = sessionId.value.isNotBlank() && runId.value.isNotBlank()

interface RunRecoveryRegistry {
    fun load(): List<RunRecoveryEntry>

    fun save(entry: RunRecoveryEntry)

    fun remove(entry: RunRecoveryEntry)

    fun clear()
}
