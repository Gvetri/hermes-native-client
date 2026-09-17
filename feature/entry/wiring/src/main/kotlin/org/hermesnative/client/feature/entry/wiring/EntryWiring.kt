package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.application.LoadEntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayClient
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.DefaultRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import java.util.concurrent.atomic.AtomicReference

object EntryWiring {
    fun createEntryStateHolder(
        context: Context,
        capabilityDiscovery: ((String, String) -> GatewayCapabilities)? = null,
        sessionGatewayFactory: ((String, String) -> SessionGatewayPort)? = null,
        runGatewayFactory: ((String, String) -> RunGatewayPort)? = null,
    ): EntryStateHolder {
        val dataSource = SharedPreferencesGatewayConnectionDataSource(context)
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val recoveryEndpoint = AtomicReference(dataSource.loadEndpoint())
        val runRecoveryRegistry =
            DefaultRunRecoveryRegistry(
                SharedPreferencesRunRecoveryStorage(context, recoveryEndpoint::get),
            )
        val initialState = LoadEntryState(repository).execute()
        val verifyGatewayConnection =
            VerifyGatewayConnection(repository) { endpoint, bearerCredential ->
                capabilityDiscovery?.invoke(endpoint, bearerCredential)
                    ?: DefaultGatewayClient(endpoint, bearerCredential).discoverCapabilities()
            }
        return EntryStateHolder(
            initialState = initialState,
            verifyGatewayConnection = verifyGatewayConnection,
            sessionGatewayFactory =
                sessionGatewayFactory ?: { endpoint, bearerCredential ->
                    DefaultGatewayClient(endpoint, bearerCredential)
                },
            runGatewayFactory =
                runGatewayFactory ?: { endpoint, bearerCredential ->
                    DefaultGatewayClient(endpoint, bearerCredential)
                },
            runRecoveryRegistry = runRecoveryRegistry,
            updateRunRecoveryEndpoint = recoveryEndpoint::set,
            persistRunRecoveryEntry = { endpoint, entry ->
                DefaultRunRecoveryRegistry(
                    SharedPreferencesRunRecoveryStorage(context) { endpoint },
                ).save(entry)
            },
            removeRunRecoveryEntry = { endpoint, entry ->
                DefaultRunRecoveryRegistry(
                    SharedPreferencesRunRecoveryStorage(context) { endpoint },
                ).remove(entry)
            },
            removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
        )
    }
}
