package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

class RemoveGatewayConnection(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
) {
    fun execute() {
        gatewayConnectionRepository.clear()
    }
}
