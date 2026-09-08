package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun verification_enters_loading_then_connected_and_persists_only_the_endpoint() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertFalse(holder.uiState.value.isVerifying)
        assertEquals("https://gateway.example/profile", repository.saved?.endpoint)
        holder.close()
    }

    @Test
    fun recoverable_failure_keeps_inputs_maps_invalid_response_to_a_safe_category_and_can_retry() {
        val repository = FakeGatewayConnectionRepository()
        var attempts = 0
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        throw GatewayException(GatewayErrorCategory.INVALID_RESPONSE)
                    }
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(GatewayErrorCategory.GATEWAY_REQUEST_FAILED, holder.uiState.value.errorCategory)
        assertEquals("https://gateway.example/profile", holder.uiState.value.endpoint)
        assertEquals("memory-only-token", holder.uiState.value.bearerCredential)
        assertFalse(holder.uiState.value.isVerifying)

        holder.onEvent(EntryUiEvent.TryAgainClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertEquals(2, attempts)
        holder.close()
    }

    private fun holder(
        repository: FakeGatewayConnectionRepository,
        verifier: (String, String) -> GatewayCapabilities,
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection = VerifyGatewayConnection(repository, discoverCapabilities = verifier),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private class FakeGatewayConnectionRepository : GatewayConnectionRepository {
        var saved: GatewayConnection? = null

        override fun load(): GatewayConnection? = saved

        override fun save(connection: GatewayConnection) {
            saved = connection
        }
    }
}
