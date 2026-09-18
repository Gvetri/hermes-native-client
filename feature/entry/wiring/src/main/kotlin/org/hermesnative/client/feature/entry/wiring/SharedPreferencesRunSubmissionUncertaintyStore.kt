package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.util.Base64
import org.hermesnative.client.feature.entry.application.normalizeGatewayEndpoint
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.presentation.PendingRunSubmissionKey
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
    ) {
        update(key) { current ->
            (current ?: StoredRecord()).copy(knownRunIds = current?.knownRunIds.orEmpty() + knownRunIds)
        }
    }

    override fun remove(key: PendingRunSubmissionKey) {
        synchronized(lock) {
            check(preferences.edit().remove(recordKey(key)).commit()) {
                "Could not remove the Gateway Run submission uncertainty marker."
            }
        }
    }

    override fun contains(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key) != null }

    override fun knownRunIds(key: PendingRunSubmissionKey): Set<RunId> = synchronized(lock) { read(key)?.knownRunIds.orEmpty() }

    override fun bindRun(
        key: PendingRunSubmissionKey,
        runId: RunId,
    ) {
        update(key) { current -> (current ?: StoredRecord()).copy(boundRunId = runId) }
    }

    override fun boundRunId(key: PendingRunSubmissionKey): RunId? = synchronized(lock) { read(key)?.boundRunId }

    override fun markSettled(key: PendingRunSubmissionKey) {
        updateIfPresent(key) { it.copy(settled = true) }
    }

    override fun isSettled(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key)?.settled == true }

    override fun markAmbiguous(key: PendingRunSubmissionKey) {
        updateIfPresent(key) { it.copy(requiresRunMatch = true) }
    }

    override fun requiresRunMatch(key: PendingRunSubmissionKey): Boolean = synchronized(lock) { read(key)?.requiresRunMatch == true }

    override fun removeIfKnownRunIdsMatch(
        key: PendingRunSubmissionKey,
        knownRunIds: Set<RunId>,
    ): Boolean =
        synchronized(lock) {
            val current = read(key)
            if (current == null || current.knownRunIds != knownRunIds) {
                false
            } else {
                check(preferences.edit().remove(recordKey(key)).commit()) {
                    "Could not remove the Gateway Run submission uncertainty marker."
                }
                true
            }
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

    private fun updateIfPresent(
        key: PendingRunSubmissionKey,
        transform: (StoredRecord) -> StoredRecord,
    ) {
        synchronized(lock) {
            val current = read(key) ?: return
            check(preferences.edit().putString(recordKey(key), encode(transform(current))).commit()) {
                "Could not update the Gateway Run submission uncertainty marker."
            }
        }
    }

    private fun read(key: PendingRunSubmissionKey): StoredRecord? = preferences.getString(recordKey(key), null)?.let(::decode)

    private fun encode(record: StoredRecord): String =
        listOf(
            FORMAT_VERSION,
            if (record.settled) "1" else "0",
            if (record.requiresRunMatch) "1" else "0",
            record.boundRunId?.value.orEmpty().let(::encodePart),
            record.knownRunIds
                .map { it.value }
                .sorted()
                .joinToString(",") { value -> encodePart(value) },
        ).joinToString("|")

    private fun decode(value: String): StoredRecord? {
        val parts = value.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != FORMAT_VERSION) return null
        return runCatching {
            StoredRecord(
                knownRunIds =
                    parts[4]
                        .takeUnless(String::isEmpty)
                        ?.split(',')
                        ?.map { encoded -> RunId(decodePart(encoded)) }
                        ?.filter { it.value.isNotBlank() }
                        ?.toSet()
                        .orEmpty(),
                boundRunId = parts[3].takeUnless(String::isEmpty)?.let { RunId(decodePart(it)) },
                settled = parts[1] == "1",
                requiresRunMatch = parts[2] == "1",
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
        val knownRunIds: Set<RunId> = emptySet(),
        val boundRunId: RunId? = null,
        val settled: Boolean = false,
        val requiresRunMatch: Boolean = false,
    )

    private companion object {
        const val PREFERENCES_NAME = "gateway_run_submission_uncertainty"
        const val RECORDS_KEY = "records"
        const val FORMAT_VERSION = "1"
        val lock = Any()
    }
}
