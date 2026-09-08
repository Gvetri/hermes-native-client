package org.hermesnative.client.feature.entry.domain

interface GatewayConnectionRepository {
    fun load(): GatewayConnection?

    fun save(connection: GatewayConnection)

    fun hasConfiguredConnection(): Boolean = load() != null
}
