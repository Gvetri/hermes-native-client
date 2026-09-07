package org.hermesnative.client.feature.entry.data

fun interface GatewayConnectionDataSource {
    fun hasConfiguredConnection(): Boolean
}

class InMemoryGatewayConnectionDataSource(
    private val isConfigured: Boolean = false,
) : GatewayConnectionDataSource {
    override fun hasConfiguredConnection(): Boolean = isConfigured
}
