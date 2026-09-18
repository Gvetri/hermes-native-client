package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.util.Base64
import org.hermesnative.client.feature.entry.application.normalizeGatewayEndpoint
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.presentation.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.presentation.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.presentation.RunSubmissionUncertaintyStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Persists response-loss markers without storing prompts, responses, or transcript data. */
class SharedPreferencesRunSubmissionUncertaintyStore(
    context: Context,
) : RunSubmissionUncertaintyStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    override fun add(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        update(key) { current ->
            if (current != null && attemptId != null && current.attemptId != attemptId) {
                return@update current
            }
            (current ?: StoredRecord(attemptId = attemptId ?: LEGACY_ATTEMPT_ID)).copy(
                knownRunIds = current?.knownRunIds.orEmpty() + knownRunIds,
            )
        }
        return attemptId == null || readAttemptId(key) == attemptId
    }

    override fun remove(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean {
        synchronized(lock) {
            val current = read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            check(preferences.edit().remove(recordKey(key)).commit()) {
                "Could not remove the Gateway Run submission uncertainty marker."
            }
            return true
        }
    }

    override fun contains(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key) != null }

    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> = synchronized(lock) { read(key)?.knownRunIds.orEmpty() }

    override fun attemptId(key: PendingRunSubmissionKey): String? = synchronized(lock) { read(key)?.attemptId }

    override fun snapshot(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? =
        synchronized(lock) {
            read(key)?.let { record ->
                RunSubmissionUncertaintySnapshot(
                    attemptId = record.attemptId,
                    knownRunIds = record.knownRunIds,
                    boundRunId = record.boundRunId,
                    settled = record.settled,
                    requiresRunMatch = record.requiresRunMatch,
                )
            }
        }

    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(boundRunId = runId) }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? = synchronized(lock) { read(key)?.boundRunId }

    override fun markSettled(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean = updateIfMatching(key, attemptId) { it.copy(settled = true) }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key)?.settled == true }

    override fun markAmbiguous(
        key: PendingRunSubmissionKey,
        attemptId: String?,
    ): Boolean {
        return updateIfMatching(key, attemptId) { it.copy(requiresRunMatch = true) }
    }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key)?.requiresRunMatch == true }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
        attemptId: String?,
    ): Boolean {
        val removed =
            synchronized(lock) {
                val current = read(key)
                if (
                    current == null ||
                    (attemptId != null && current.attemptId != attemptId) ||
                    current.knownRunIds != knownRunIds
                ) {
                    false
                } else {
                    check(preferences.edit().remove(recordKey(key)).commit()) {
                        "Could not remove the Gateway Run submission uncertainty marker."
                    }
                    true
                }
            }
        return removed
    }

    private fun update(
        key: PendingRunSubmissionKey,
        transform: (StoredRecord?) -> StoredRecord,
    ) {
        synchronized(lock) {
            val next = transform(read(key))
            check(preferences.edit().putString(recordKey(key), encode(next)).commit()) {
                "Could not persist the Gateway Run submission uncertainty marker."
            }
        }
    }

    private fun updateIfMatching(
        key: PendingRunSubmissionKey,
        attemptId: String?,
        transform: (StoredRecord) -> StoredRecord,
    ): Boolean {
        synchronized(lock) {
            val current = read(key) ?: return false
            if (attemptId != null && current.attemptId != attemptId) return false
            check(preferences.edit().putString(recordKey(key), encode(transform(current))).commit()) {
                "Could not update the Gateway Run submission uncertainty marker."
            }
            return true
        }
    }

    private fun readAttemptId(key: PendingRunSubmissionKey): String? = synchronized(lock) { read(key)?.attemptId }

    private fun read(key: PendingRunSubmissionKey): StoredRecord? = preferences.getString(recordKey(key), null)?.let(::decode)

    private fun encode(record: StoredRecord): String =
        listOf(
            FORMAT_VERSION,
            encodePart(record.attemptId),
            if (record.settled) "1" else "0",
            if (record.requiresRunMatch) "1" else "0",
            record.boundRunId?.value.orEmpty().let(::encodePart),
            record.knownRunIds
                .map { it.value }
                .sorted()
                .joinToString(",") { value -> encodePart(value) },
        ).joinToString("|")

    private fun decode(value: String): StoredRecord? {
        val parts = value.split('|', limit = 6)
        val isCurrentFormat = parts.size == 6 && parts[0] == FORMAT_VERSION
        val isLegacyFormat = parts.size == 5 && parts[0] == LEGACY_FORMAT_VERSION
        if (!isCurrentFormat && !isLegacyFormat) return null
        return runCatching {
            StoredRecord(
                attemptId = if (isCurrentFormat) decodePart(parts[1]) else LEGACY_ATTEMPT_ID,
                knownRunIds =
                    parts[if (isCurrentFormat) 5 else 4]
                        .takeUnless(String::isEmpty)
                        ?.split(',')
                        ?.map { encoded -> RunId(decodePart(encoded)) }
                        ?.filter { it.value.isNotBlank() }
                        ?.toSet()
                        .orEmpty(),
                boundRunId =
                    parts[if (isCurrentFormat) 4 else 3]
                        .takeUnless(String::isEmpty)
                        ?.let { RunId(decodePart(it)) },
                settled = parts[if (isCurrentFormat) 2 else 1] == "1",
                requiresRunMatch = parts[if (isCurrentFormat) 3 else 2] == "1",
            )
        }.getOrNull()
    }

    private fun recordKey(key: PendingRunSubmissionKey): String =
        "$RECORDS_KEY.${endpointNamespace(key.endpoint)}.${encodePart(key.sessionId.value)}"

    private fun encodePart(value: String): String = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private fun decodePart(value: String): String = Base64.decode(value, Base64.NO_WRAP).toString(StandardCharsets.UTF_8)

    private fun endpointNamespace(endpoint: String): String {
        val canonicalEndpoint = runCatching { normalizeGatewayEndpoint(endpoint) }.getOrDefault(endpoint.trim())
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonicalEndpoint.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class StoredRecord(
        val attemptId: String,
        val knownRunIds: Set<RunId> = emptySet(),
        val boundRunId: RunId? = null,
        val settled: Boolean = false,
        val requiresRunMatch: Boolean = false,
    )

    private companion object {
        const val PREFERENCES_NAME = "gateway_run_submission_uncertainty"
        const val RECORDS_KEY = "records"
        const val FORMAT_VERSION = "2"
        const val LEGACY_FORMAT_VERSION = "1"
        const val LEGACY_ATTEMPT_ID = "legacy"
        val lock = Any()
    }
}
