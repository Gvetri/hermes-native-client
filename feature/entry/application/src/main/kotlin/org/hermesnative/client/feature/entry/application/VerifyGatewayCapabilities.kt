package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCapabilityPort

class VerifyGatewayCapabilities(
    private val gateway: GatewayCapabilityPort,
) {
    fun execute(): GatewayCapabilities = gateway.discoverCapabilities()
}
