package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultGatewayConnectionRepositoryTest {
    @Test
    fun loads_and_saves_the_non_secret_gateway_connection() {
        val dataSource = InMemoryGatewayConnectionDataSource()
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val connection = GatewayConnection("https://gateway.example/profile")

        repository.save(connection)

        assertEquals(connection, repository.load())
        assertEquals("https://gateway.example/profile", dataSource.loadEndpoint())
    }
}
