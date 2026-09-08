package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.data.GatewayConnectionDataSource

class SharedPreferencesGatewayConnectionDataSource(
    context: Context,
) : GatewayConnectionDataSource {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    override fun loadEndpoint(): String? = preferences.getString(ENDPOINT_KEY, null)

    override fun saveEndpoint(endpoint: String) {
        preferences.edit().putString(ENDPOINT_KEY, endpoint).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "gateway_connection"
        const val ENDPOINT_KEY = "endpoint"
    }
}
