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
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryStateHolderDependencies
import org.hermesnative.client.feature.entry.presentation.LocalDiagnosticsPorts
import java.util.concurrent.atomic.AtomicReference

object EntryWiring {
    fun createEntryStateHolder(
        context: Context,
        overrides: EntryWiringOverrides = EntryWiringOverrides(),
        clientVersion: String = androidClientVersion(context),
    ): EntryStateHolder {
        val dataSource = SharedPreferencesGatewayConnectionDataSource(context)
        val credentialStore = AndroidKeyStoreGatewayCredentialStore(context)
        val repository = DefaultGatewayConnectionRepository(dataSource, credentialStore)
        val recoveryEndpoint = AtomicReference(dataSource.loadEndpoint())
        val runRecoveryRegistry = buildRunRecoveryRegistry(context, recoveryEndpoint)
        val initialState = LoadEntryState(repository).execute()
        val verifyGatewayConnection = buildVerifyGatewayConnection(repository, overrides)
        val runSubmissionUncertaintyStore = SharedPreferencesRunSubmissionUncertaintyStore(context)
        val localDiagnosticsBuffer =
            RollingLocalDiagnosticsBuffer(FileLocalDiagnosticsStorage(context))
        return EntryStateHolder(
            initialState = initialState,
            verifyGatewayConnection = verifyGatewayConnection,
            scope = entryScope(overrides.coroutineScope),
            sessionGatewayFactory = overrides.sessionGatewayFactory ?: ::newGatewayClient,
            runGatewayFactory = overrides.runGatewayFactory ?: ::newGatewayClient,
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
                        buildConnectionRemovalUseCase(
                            repository,
                            runRecoveryRegistry,
                            runSubmissionUncertaintyStore,
                            localDiagnosticsBuffer,
                        ),
                    localDiagnostics = buildDiagnosticsPorts(context, localDiagnosticsBuffer, clientVersion),
                    runSubmissionUncertaintyStore = runSubmissionUncertaintyStore,
                    runStatusNotificationSettingsStore = SharedPreferencesRunStatusNotificationSettingsStore(context),
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

private fun buildRunRecoveryRegistry(
    context: Context,
    recoveryEndpoint: AtomicReference<String?>,
): EndpointScopedRunRecoveryRegistry =
    EndpointScopedRunRecoveryRegistry(
        endpointProvider = recoveryEndpoint::get,
        storageForEndpoint = { endpoint ->
            SharedPreferencesRunRecoveryStorage(context) { endpoint }
        },
    )

private fun buildVerifyGatewayConnection(
    repository: GatewayConnectionRepository,
    overrides: EntryWiringOverrides,
): VerifyGatewayConnection =
    VerifyGatewayConnection(repository) { endpoint, bearerCredential ->
        overrides.capabilityDiscovery?.invoke(endpoint, bearerCredential)
            ?: DefaultGatewayClient(endpoint, bearerCredential).discoverCapabilities()
    }

private fun buildConnectionRemovalUseCase(
    repository: GatewayConnectionRepository,
    runRecoveryRegistry: EndpointScopedRunRecoveryRegistry,
    runSubmissionUncertaintyStore: SharedPreferencesRunSubmissionUncertaintyStore,
    localDiagnosticsBuffer: RollingLocalDiagnosticsBuffer,
): RemoveGatewayConnection =
    RemoveGatewayConnection(repository) { endpoint ->
        runRecoveryRegistry.clearForEndpoint(endpoint)
        endpoint?.let(runSubmissionUncertaintyStore::clearEndpoint)
        localDiagnosticsBuffer.clear()
    }

private fun buildDiagnosticsPorts(
    context: Context,
    buffer: RollingLocalDiagnosticsBuffer,
    clientVersion: String,
): LocalDiagnosticsPorts =
    LocalDiagnosticsPorts(
        recorder = buffer,
        store = buffer,
        exporter =
            AndroidLocalDiagnosticsExporter(
                context = context,
                buffer = buffer,
                clientVersion = clientVersion,
            ),
    )

private fun newGatewayClient(
    endpoint: String,
    bearerCredential: String,
): DefaultGatewayClient = DefaultGatewayClient(endpoint, bearerCredential)
