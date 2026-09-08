package org.hermesnative.client.feature.entry.domain

enum class GatewayErrorCategory(
    val safeMessage: String,
) {
    INVALID_ADDRESS("Invalid Gateway address. Enter one HTTPS Gateway endpoint."),
    SECURE_CONNECTION_FAILED("Secure connection failed. Check the Gateway certificate and hostname."),
    AUTHENTICATION_FAILED("Authentication failed. Check the Gateway credential."),
    REQUIRED_FEATURE_UNAVAILABLE("Required feature unavailable. This Gateway does not support the client contract."),
    GATEWAY_REQUEST_FAILED("Gateway request failed. Try again."),
    INVALID_RESPONSE("Gateway returned an invalid response. Try again or contact the Gateway administrator."),
}

class GatewayException(
    val category: GatewayErrorCategory,
    message: String = category.safeMessage,
) : RuntimeException(message)
