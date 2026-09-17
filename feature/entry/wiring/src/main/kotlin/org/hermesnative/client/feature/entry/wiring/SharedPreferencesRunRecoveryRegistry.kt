package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.util.Base64
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.SessionId
import java.nio.charset.StandardCharsets

class SharedPreferencesRunRecoveryRegistry(
    context: Context,
) : RunRecoveryRegistry {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
    private val lock = Any()

    override fun load(): List<RunRecoveryEntry> =
        synchronized(lock) {
            storedEntries()
                .mapNotNull(::decode)
                .distinct()
                .sortedWith(compareBy({ it.sessionId.value }, { it.runId.value }))
        }

    override fun save(entry: RunRecoveryEntry) {
        updateEntries { it + encode(entry) }
    }

    override fun remove(entry: RunRecoveryEntry) {
        updateEntries { it - encode(entry) }
    }

    override fun clear() {
        synchronized(lock) {
            preferences.edit().remove(ENTRIES_KEY).apply()
        }
    }

    private fun updateEntries(transform: (Set<String>) -> Set<String>) {
        synchronized(lock) {
            val updated = transform(storedEntries())
            preferences.edit().putStringSet(ENTRIES_KEY, updated).apply()
        }
    }

    private fun storedEntries(): Set<String> = preferences.getStringSet(ENTRIES_KEY, emptySet()).orEmpty().toSet()

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
