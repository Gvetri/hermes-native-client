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
            previousCredential != null -> credentialStore.clear()
        }
        try {
            dataSource.saveEndpoint(connection.endpoint)
        } catch (expectedError: Exception) {
            runCatching {
                previousCredential?.let(credentialStore::save) ?: credentialStore.clear()
            }
            throw expectedError
        }
    }

    override fun clear() {
        var failure: Exception? = null
        try {
            credentialStore.clear()
        } catch (expectedError: Exception) {
            failure = expectedError
        }
        try {
            dataSource.clearEndpoint()
        } catch (expectedError: Exception) {
            failure = failure ?: expectedError
        }
        failure?.let { throw it }
    }
}
