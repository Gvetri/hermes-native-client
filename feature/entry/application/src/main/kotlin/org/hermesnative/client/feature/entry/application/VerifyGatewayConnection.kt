package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionPersistenceException
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.satisfies
import java.util.Locale

class VerifyGatewayConnection(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
    private val manifest: GatewayCapabilityManifest = PublicBetaGatewayCapabilityManifest.current,
    private val discoverCapabilities: (endpoint: String, bearerCredential: String) -> GatewayCapabilities,
) {
    fun execute(
        endpoint: String,
        bearerCredential: String,
        saveCredential: Boolean = false,
    ): GatewayCapabilities {
        val capabilities = verify(endpoint, bearerCredential)
        persist(
            endpoint = endpoint,
            bearerCredential = bearerCredential,
            saveCredential = saveCredential,
        )
        return capabilities
    }

    fun executeWithoutPersistence(
        endpoint: String,
        bearerCredential: String,
    ): GatewayCapabilities = verify(endpoint, bearerCredential)

    fun persist(
        endpoint: String,
        bearerCredential: String? = null,
        saveCredential: Boolean = false,
    ) {
        val connection =
            GatewayConnection(
                endpoint = normalizeGatewayEndpoint(endpoint),
                bearerCredential = bearerCredential?.takeIf { saveCredential },
            )
        try {
            gatewayConnectionRepository.save(connection)
        } catch (error: GatewayConnectionPersistenceException) {
            throw error
        } catch (error: Exception) {
            throw GatewayConnectionPersistenceException(error)
        }
    }

    private fun verify(
        endpoint: String,
        bearerCredential: String,
    ): GatewayCapabilities {
        val normalizedEndpoint = normalizeGatewayEndpoint(endpoint)
        if (bearerCredential.isBlank()) {
            throw GatewayException(GatewayErrorCategory.AUTHENTICATION_FAILED)
        }

        val capabilities = discoverCapabilities(normalizedEndpoint, bearerCredential)
        if (!capabilities.satisfies(manifest)) {
            throw GatewayException(GatewayErrorCategory.REQUIRED_FEATURE_UNAVAILABLE)
        }
        return capabilities
    }
}

fun normalizeGatewayEndpoint(endpoint: String): String = GatewayEndpointValidator.normalize(endpoint)

private object GatewayEndpointValidator {
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
    private const val HEX_RADIX = 16
    private const val HTTPS_PORT = 443
    private val percentEscapePattern = Regex("%([0-9a-fA-F]{2})")
    private val endpointPattern =
        Regex(
            """(?i)^https://([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?""" +
                """((?:/[a-z0-9._~!&'()*+,;=:@%/-]*)?)\z""",
        )

    fun normalize(endpoint: String): String {
        val normalizedEndpoint = endpoint.trim()
        val match = endpointPattern.matchEntire(normalizedEndpoint) ?: throw invalidAddress()
        val (hostMatch, portMatch, pathMatch) = match.destructured
        val host = hostMatch.lowercase(Locale.ROOT)
        val port = portMatch.takeIf(String::isNotEmpty)?.toIntOrNull()
        val path = pathMatch.trimEnd('/')

        if (
            invalidHost(host) ||
            invalidPort(port) ||
            !hasValidPercentEncoding(normalizedEndpoint)
        ) {
            throw invalidAddress()
        }
        val canonicalPath =
            path.replace(percentEscapePattern) { escape ->
                val character = escape.groupValues[1].toInt(HEX_RADIX).toChar()
                val isUnreservedLiteral =
                    character in 'a'..'z' ||
                        character in 'A'..'Z' ||
                        character in '0'..'9' ||
                        character in "-._~"
                if (isUnreservedLiteral) {
                    character.toString()
                } else {
                    escape.value
                }
            }
        return buildString {
            append("https://")
            append(host)
            port?.takeUnless { it == HTTPS_PORT }?.let { append(':').append(it) }
            append(canonicalPath)
        }
    }

    private fun invalidHost(host: String): Boolean = host.split('.').any(::invalidHostLabel)

    private fun invalidPort(port: Int?): Boolean = port != null && port !in MIN_PORT..MAX_PORT

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
