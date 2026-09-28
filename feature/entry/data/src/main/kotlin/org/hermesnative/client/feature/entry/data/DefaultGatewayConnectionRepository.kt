package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayCredentialStore

class DefaultGatewayConnectionRepository(
    private val dataSource: GatewayConnectionDataSource,
    private val credentialStore: GatewayCredentialStore = InMemoryGatewayCredentialStore(),
) : GatewayConnectionRepository {
    override fun load(): GatewayConnection? =
        dataSource.loadEndpoint()?.let { endpoint ->
            GatewayConnection(endpoint = endpoint, bearerCredential = credentialStore.load())
        }

    override fun save(connection: GatewayConnection) {
        val previousCredential = credentialStore.load()
        val credential = connection.bearerCredential
        when {
            credential != null -> credentialStore.save(credential)
            // Only discard stored material when a credential was actually
            // persisted, so an endpoint-only save does not churn the
            // AndroidKeyStore key that protects the record.
            previousCredential != null -> credentialStore.clear()
        }
        try {
            dataSource.saveEndpoint(connection.endpoint)
        } catch (error: Exception) {
            runCatching {
                previousCredential?.let(credentialStore::save) ?: credentialStore.clear()
            }
            throw error
        }
    }

    override fun clear() {
        var failure: Exception? = null
        try {
            credentialStore.clear()
        } catch (error: Exception) {
            failure = error
        }
        try {
            dataSource.clearEndpoint()
        } catch (error: Exception) {
            failure = failure ?: error
        }
        failure?.let { throw it }
    }
}
