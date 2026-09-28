package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayCredentialStore

interface GatewayConnectionDataSource {
    fun loadEndpoint(): String?

    fun saveEndpoint(endpoint: String)

    fun clearEndpoint() = Unit
}

class InMemoryGatewayConnectionDataSource(
    private var endpoint: String? = null,
) : GatewayConnectionDataSource {
    override fun loadEndpoint(): String? = endpoint

    override fun saveEndpoint(endpoint: String) {
        this.endpoint = endpoint
    }

    override fun clearEndpoint() {
        endpoint = null
    }
}

class InMemoryGatewayCredentialStore(
    private var credential: String? = null,
) : GatewayCredentialStore {
    override fun load(): String? = credential

    override fun save(credential: String) {
        this.credential = credential
    }

    override fun clear() {
        credential = null
    }
}
