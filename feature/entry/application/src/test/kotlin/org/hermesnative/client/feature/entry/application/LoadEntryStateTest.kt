package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.junit.Assert.assertFalse
import org.junit.Test

class LoadEntryStateTest {
    @Test
    fun reports_an_entry_state_when_no_gateway_connection_is_configured() {
        val repository = FakeGatewayConnectionRepository(isConfigured = false)

        val state = LoadEntryState(repository).execute()

        assertFalse(state.isGatewayConnectionConfigured)
    }

    private class FakeGatewayConnectionRepository(
        private val isConfigured: Boolean,
    ) : GatewayConnectionRepository {
        override fun hasConfiguredConnection(): Boolean = isConfigured
    }
}
