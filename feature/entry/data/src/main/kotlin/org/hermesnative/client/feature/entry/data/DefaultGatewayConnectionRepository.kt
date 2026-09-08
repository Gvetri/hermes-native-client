package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

class DefaultGatewayConnectionRepository(
    private val dataSource: GatewayConnectionDataSource,
) : GatewayConnectionRepository {
    override fun load(): GatewayConnection? = dataSource.loadEndpoint()?.let(::GatewayConnection)

    override fun save(connection: GatewayConnection) {
        dataSource.saveEndpoint(connection.endpoint)
    }
}
