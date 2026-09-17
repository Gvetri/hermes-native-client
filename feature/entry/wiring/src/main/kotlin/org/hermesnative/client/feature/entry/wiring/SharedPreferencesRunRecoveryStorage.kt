package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.util.Base64
import org.hermesnative.client.feature.entry.data.RunRecoveryStorage
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.isValid
import java.nio.charset.StandardCharsets

/** Android-only persistence bridge; registry behavior remains in the JVM data module. */
class SharedPreferencesRunRecoveryStorage(
    context: Context,
) : RunRecoveryStorage {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    override fun load(): Set<RunRecoveryEntry> =
        preferences
            .getStringSet(ENTRIES_KEY, emptySet())
            .orEmpty()
            .mapNotNull(::decode)
            .filter(RunRecoveryEntry::isValid)
            .toSet()

    override fun save(entries: Set<RunRecoveryEntry>) {
        check(
            preferences
                .edit()
                .putStringSet(ENTRIES_KEY, entries.filter(RunRecoveryEntry::isValid).map(::encode).toSet())
                .commit(),
        ) { "Could not persist Gateway Run recovery metadata." }
    }

    private fun encode(entry: RunRecoveryEntry): String = "${encode(entry.sessionId.value)}.${encode(entry.runId.value)}"

    private fun decode(value: String): RunRecoveryEntry? {
        val parts = value.split('.', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            RunRecoveryEntry(
                sessionId = SessionId(decodePart(parts[0])),
                runId = RunId(decodePart(parts[1])),
            )
        }.getOrNull()
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    private fun decodePart(value: String): String = Base64.decode(value, Base64.NO_WRAP).toString(StandardCharsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "gateway_run_recovery"
        const val ENTRIES_KEY = "entries"
    }
}
