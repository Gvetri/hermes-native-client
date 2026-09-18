package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.util.Base64
import org.hermesnative.client.feature.entry.application.normalizeGatewayEndpoint
import org.hermesnative.client.feature.entry.data.DefaultRunSubmissionUncertaintyStore
import org.hermesnative.client.feature.entry.data.RunSubmissionUncertaintyStorage
import org.hermesnative.client.feature.entry.domain.PendingRunSubmissionKey
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintySnapshot
import org.hermesnative.client.feature.entry.domain.RunSubmissionUncertaintyStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Persists response-loss markers without storing prompts, responses, or transcript data. */
class SharedPreferencesRunSubmissionUncertaintyStore(
    context: Context,
) : RunSubmissionUncertaintyStore by DefaultRunSubmissionUncertaintyStore(SharedPreferencesRunSubmissionUncertaintyStorage(context))

private class SharedPreferencesRunSubmissionUncertaintyStorage(
    context: Context,
) : RunSubmissionUncertaintyStorage {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    override fun read(key: PendingRunSubmissionKey): RunSubmissionUncertaintySnapshot? =
        preferences.getString(recordKey(key), null)?.let(::decode)

    override fun write(
        key: PendingRunSubmissionKey,
        snapshot: RunSubmissionUncertaintySnapshot,
    ) {
        check(preferences.edit().putString(recordKey(key), encode(snapshot)).commit()) {
            "Could not persist the Gateway Run submission uncertainty marker."
        }
    }

    override fun remove(key: PendingRunSubmissionKey) {
        check(preferences.edit().remove(recordKey(key)).commit()) {
            "Could not remove the Gateway Run submission uncertainty marker."
        }
    }

    private fun encode(record: RunSubmissionUncertaintySnapshot): String =
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

    private fun decode(value: String): RunSubmissionUncertaintySnapshot? {
        val parts = value.split('|', limit = 6)
        val isCurrentFormat = parts.size == 6 && parts[0] == FORMAT_VERSION
        val isLegacyFormat = parts.size == 5 && parts[0] == LEGACY_FORMAT_VERSION
        if (!isCurrentFormat && !isLegacyFormat) return null
        return runCatching {
            RunSubmissionUncertaintySnapshot(
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

    private companion object {
        const val PREFERENCES_NAME = "gateway_run_submission_uncertainty"
        const val RECORDS_KEY = "records"
        const val FORMAT_VERSION = "2"
        const val LEGACY_FORMAT_VERSION = "1"
        const val LEGACY_ATTEMPT_ID = "legacy"
    }
}
