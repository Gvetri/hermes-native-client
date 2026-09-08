package org.hermesnative.client.feature.entry.data

interface GatewayConnectionDataSource {
    fun loadEndpoint(): String?

    fun saveEndpoint(endpoint: String)
}

class InMemoryGatewayConnectionDataSource(
    private var endpoint: String? = null,
) : GatewayConnectionDataSource {
    override fun loadEndpoint(): String? = endpoint

    override fun saveEndpoint(endpoint: String) {
        this.endpoint = endpoint
    }
}
