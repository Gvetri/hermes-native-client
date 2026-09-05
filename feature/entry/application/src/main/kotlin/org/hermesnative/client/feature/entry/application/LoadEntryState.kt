package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

data class EntryState(
    val isGatewayConnectionConfigured: Boolean,
)

class LoadEntryState(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
) {
    fun execute(): EntryState =
        EntryState(
            isGatewayConnectionConfigured = gatewayConnectionRepository.hasConfiguredConnection(),
        )
}
