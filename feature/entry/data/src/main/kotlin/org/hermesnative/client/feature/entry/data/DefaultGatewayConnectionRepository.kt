package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

class DefaultGatewayConnectionRepository(
    private val dataSource: GatewayConnectionDataSource,
) : GatewayConnectionRepository {
    override fun hasConfiguredConnection(): Boolean = dataSource.hasConfiguredConnection()
}
