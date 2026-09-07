package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.application.EntryState
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryStateHolderTest {
    @Test
    fun add_gateway_connection_event_requests_connection_setup() {
        val stateHolder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = false),
            )

        stateHolder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)

        assertTrue(stateHolder.uiState.value.connectionSetupRequested)
    }
}
