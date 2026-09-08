package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest

class VerifyGatewayConnection(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
    private val manifest: GatewayCapabilityManifest = PublicBetaGatewayCapabilityManifest.current,
    private val discoverCapabilities: (endpoint: String, bearerCredential: String) -> GatewayCapabilities,
) {
    fun execute(
        endpoint: String,
        bearerCredential: String,
    ): GatewayCapabilities {
        val normalizedEndpoint = endpoint.trim()
        if (normalizedEndpoint.isBlank()) {
            throw GatewayException(GatewayErrorCategory.INVALID_ADDRESS)
        }
        if (bearerCredential.isBlank()) {
            throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
        }

        val capabilities = discoverCapabilities(normalizedEndpoint, bearerCredential)
        if (!manifest.requiredIdentifiers.all(capabilities::supports)) {
            throw GatewayException(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE)
        }

        gatewayConnectionRepository.save(GatewayConnection(normalizedEndpoint))
        return capabilities
    }
}
