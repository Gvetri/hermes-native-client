package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun cleanup_still_runs_when_the_saved_endpoint_cannot_be_read() {
        val repository = FakeGatewayConnectionRepository(GatewayConnection("https://gateway.example", "token"))
        repository.failOnLoad = true
        var clearedEndpoint: String? = "unset"
        var clearedConnectionSpecificData = false

        val failure =
            runCatching {
                RemoveGatewayConnection(repository) { endpoint ->
                    clearedEndpoint = endpoint
                    clearedConnectionSpecificData = true
                }.execute()
            }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(repository.cleared)
        assertTrue(clearedConnectionSpecificData)
        assertNull(clearedEndpoint)
    }

    private class FakeGatewayConnectionRepository(
        private var connection: GatewayConnection?,
    ) : GatewayConnectionRepository {
        var failOnLoad = false
        var cleared = false

        override fun load(): GatewayConnection? {
            if (failOnLoad) throw ClassCastException("The stored Gateway endpoint is not a string.")
            return connection
        }

        override fun save(connection: GatewayConnection) {
            this.connection = connection
        }

        override fun clear() {
            connection = null
            cleared = true
        }
    }
}
