package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LoadEntryStateTest {
    @Test
    fun reports_an_entry_state_when_no_gateway_connection_is_configured() {
        val repository = FakeGatewayConnectionRepository(endpoint = null)

        val state = LoadEntryState(repository).execute()

        assertFalse(state.isGatewayConnectionConfigured)
        assertEquals(null, state.configuredEndpoint)
    }

    @Test
    fun loads_the_persisted_endpoint_without_a_credential() {
        val repository = FakeGatewayConnectionRepository(endpoint = "https://gateway.example/profile")

        val state = LoadEntryState(repository).execute()

        assertEquals(true, state.isGatewayConnectionConfigured)
        assertEquals("https://gateway.example/profile", state.configuredEndpoint)
    }

    private class FakeGatewayConnectionRepository(
        private val endpoint: String?,
    ) : GatewayConnectionRepository {
        override fun load(): GatewayConnection? = endpoint?.let(::GatewayConnection)

        override fun save(connection: GatewayConnection) = Unit
    }
}
