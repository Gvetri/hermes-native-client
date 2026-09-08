package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

data class EntryState(
    val isGatewayConnectionConfigured: Boolean = false,
    val configuredEndpoint: String? = null,
)

class LoadEntryState(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
) {
    fun execute(): EntryState {
        val configuredEndpoint = gatewayConnectionRepository.load()?.endpoint
        return EntryState(
            isGatewayConnectionConfigured = configuredEndpoint != null,
            configuredEndpoint = configuredEndpoint,
        )
    }
}
