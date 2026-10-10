package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.LoadEntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayClient
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.EndpointScopedRunRecoveryRegistry
import org.hermesnative.client.feature.entry.data.RollingLocalDiagnosticsBuffer
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryStateHolderDependencies
import org.hermesnative.client.feature.entry.presentation.LocalDiagnosticsPorts
import java.util.concurrent.atomic.AtomicReference

object EntryWiring {
    fun createEntryStateHolder(
        context: Context,
        capabilityDiscovery: ((String, String) -> GatewayCapabilities)? = null,
        sessionGatewayFactory: ((String, String) -> SessionGatewayPort)? = null,
        runGatewayFactory: ((String, String) -> RunGatewayPort)? = null,
        coroutineScope: CoroutineScope? = null,
        clientVersion: String = androidClientVersion(context),
    ): EntryStateHolder {
        val dataSource = SharedPreferencesGatewayConnectionDataSource(context)
        val credentialStore = AndroidKeyStoreGatewayCredentialStore(context)
        val repository = DefaultGatewayConnectionRepository(dataSource, credentialStore)
        val recoveryEndpoint = AtomicReference(dataSource.loadEndpoint())
        val runRecoveryRegistry =
            EndpointScopedRunRecoveryRegistry(
                endpointProvider = recoveryEndpoint::get,
                storageForEndpoint = { endpoint ->
                    SharedPreferencesRunRecoveryStorage(context) { endpoint }
                },
            )
        val initialState = LoadEntryState(repository).execute()
        val verifyGatewayConnection =
            VerifyGatewayConnection(repository) { endpoint, bearerCredential ->
                capabilityDiscovery?.invoke(endpoint, bearerCredential)
                    ?: DefaultGatewayClient(endpoint, bearerCredential).discoverCapabilities()
            }
        val runSubmissionUncertaintyStore = SharedPreferencesRunSubmissionUncertaintyStore(context)
        val localDiagnosticsBuffer =
            RollingLocalDiagnosticsBuffer(FileLocalDiagnosticsStorage(context))
        return EntryStateHolder(
            initialState = initialState,
            verifyGatewayConnection = verifyGatewayConnection,
            scope = entryScope(coroutineScope),
            sessionGatewayFactory =
                sessionGatewayFactory ?: { endpoint, bearerCredential ->
                    DefaultGatewayClient(endpoint, bearerCredential)
                },
            runGatewayFactory =
                runGatewayFactory ?: { endpoint, bearerCredential ->
                    DefaultGatewayClient(endpoint, bearerCredential)
                },
            dependencies =
                EntryStateHolderDependencies(
                    runRecoveryRegistry = runRecoveryRegistry,
                    updateRunRecoveryEndpoint = recoveryEndpoint::set,
                    persistRunRecoveryEntry = { endpoint, entry ->
                        runRecoveryRegistry.saveForEndpoint(endpoint, entry)
                    },
                    removeRunRecoveryEntry = { endpoint, entry ->
                        runRecoveryRegistry.removeForEndpoint(endpoint, entry)
                    },
                    removeGatewayConnectionUseCase =
                        RemoveGatewayConnection(repository) { endpoint ->
                            runRecoveryRegistry.clearForEndpoint(endpoint)
                            endpoint?.let(runSubmissionUncertaintyStore::clearEndpoint)
                            localDiagnosticsBuffer.clear()
                        },
                    localDiagnostics =
                        LocalDiagnosticsPorts(
                            recorder = localDiagnosticsBuffer,
                            store = localDiagnosticsBuffer,
                            exporter =
                                AndroidLocalDiagnosticsExporter(
                                    context = context,
                                    buffer = localDiagnosticsBuffer,
                                    clientVersion = clientVersion,
                                ),
                        ),
                    runSubmissionUncertaintyStore = runSubmissionUncertaintyStore,
                    runStatusNotificationSettingsStore =
                        SharedPreferencesRunStatusNotificationSettingsStore(context),
                    runStatusNotificationPermission = AndroidRunStatusNotificationPermission(context),
                    runStatusNotifier = SystemRunStatusNotifier(context),
                ),
        )
    }

    private fun entryScope(
        coroutineScope: CoroutineScope?,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)
}
