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
        val normalizedEndpoint = GatewayEndpointValidator.normalize(endpoint)
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

private object GatewayEndpointValidator {
    private val endpointPattern =
        Regex(
            """(?i)^https://([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?(?:/[a-z0-9._~!&'()*+,;=:@%/-]*)?\z""",
        )

    fun normalize(endpoint: String): String {
        val normalizedEndpoint = endpoint.trim()
        val match = endpointPattern.matchEntire(normalizedEndpoint) ?: throw invalidAddress()
        val host = match.groupValues[1]
        val port = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()

        if (
            host.split('.').any(::invalidHostLabel) ||
            (port != null && port !in 1..65535) ||
            !hasValidPercentEncoding(normalizedEndpoint)
        ) {
            throw invalidAddress()
        }
        return normalizedEndpoint
    }

    private fun invalidHostLabel(label: String): Boolean =
        label.isEmpty() ||
            !label.first().isLetterOrDigit() ||
            !label.last().isLetterOrDigit() ||
            label.any { !it.isLetterOrDigit() && it != '-' }

    private fun hasValidPercentEncoding(value: String): Boolean =
        value.indices.none { index ->
            value[index] == '%' &&
                (
                    index + 2 >= value.length ||
                        !value[index + 1].isHexDigit() ||
                        !value[index + 2].isHexDigit()
                )
        }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun invalidAddress(): GatewayException = GatewayException(GatewayErrorCategory.INVALID_ADDRESS)
}
