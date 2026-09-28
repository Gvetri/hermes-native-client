package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

data class EntryState(
    val isGatewayConnectionConfigured: Boolean = false,
    val configuredEndpoint: String? = null,
    val configuredCredential: String? = null,
)

class LoadEntryState(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
) {
    fun execute(): EntryState {
        val connection = gatewayConnectionRepository.load()
        val configuredEndpoint = connection?.endpoint
        return EntryState(
            isGatewayConnectionConfigured = configuredEndpoint != null,
            configuredEndpoint = configuredEndpoint,
            configuredCredential = connection?.bearerCredential,
        )
    }
}
