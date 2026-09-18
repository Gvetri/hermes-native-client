package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationSettingsStore

/** SharedPreferences-backed persistence for the Run status notification preference. */
class SharedPreferencesRunStatusNotificationSettingsStore(
    context: Context,
) : RunStatusNotificationSettingsStore {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun loadEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    override fun saveEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "run_status_notification_settings"
        const val KEY_ENABLED = "enabled"
    }
}
