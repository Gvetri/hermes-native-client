package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.application.LoadEntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayClient
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder

object EntryWiring {
    fun createEntryStateHolder(context: Context): EntryStateHolder {
        val dataSource = SharedPreferencesGatewayConnectionDataSource(context)
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val initialState = LoadEntryState(repository).execute()
        val verifyGatewayConnection =
            VerifyGatewayConnection(repository) { endpoint, bearerCredential ->
                DefaultGatewayClient(endpoint, bearerCredential).discoverCapabilities()
            }
        return EntryStateHolder(initialState, verifyGatewayConnection)
    }
}
