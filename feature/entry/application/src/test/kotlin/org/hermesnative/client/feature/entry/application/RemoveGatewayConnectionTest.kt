package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveGatewayConnectionTest {
    @Test
    fun removes_the_connection_and_all_connection_specific_local_data() {
        val repository = FakeGatewayConnectionRepository(GatewayConnection("https://gateway.example", "token"))
        var clearedEndpoint: String? = null

        RemoveGatewayConnection(repository) { endpoint -> clearedEndpoint = endpoint }.execute()

        assertEquals("https://gateway.example", clearedEndpoint)
        assertNull(repository.load())
    }

    private class FakeGatewayConnectionRepository(
        private var connection: GatewayConnection?,
    ) : GatewayConnectionRepository {
        override fun load(): GatewayConnection? = connection

        override fun save(connection: GatewayConnection) {
            this.connection = connection
        }

        override fun clear() {
            connection = null
        }
    }
}
