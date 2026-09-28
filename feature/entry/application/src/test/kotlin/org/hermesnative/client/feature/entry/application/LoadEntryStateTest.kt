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
        assertEquals(null, state.configuredCredential)
    }

    @Test
    fun loads_an_opt_in_credential_without_changing_the_endpoint_contract() {
        val repository =
            FakeGatewayConnectionRepository(
                endpoint = "https://gateway.example/profile",
                credential = "secure-token",
            )

        val state = LoadEntryState(repository).execute()

        assertEquals("https://gateway.example/profile", state.configuredEndpoint)
        assertEquals("secure-token", state.configuredCredential)
    }

    private class FakeGatewayConnectionRepository(
        private val endpoint: String?,
        private val credential: String? = null,
    ) : GatewayConnectionRepository {
        override fun load(): GatewayConnection? = endpoint?.let { GatewayConnection(it, credential) }

        override fun save(connection: GatewayConnection) = Unit
    }
}
