package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

class RemoveGatewayConnection(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
    private val clearConnectionSpecificData: (String?) -> Unit = {},
) {
    fun execute() {
        var failure: Exception? = null
        val endpoint =
            try {
                gatewayConnectionRepository.load()?.endpoint
            } catch (expectedError: Exception) {
                failure = expectedError
                null
            }
        try {
            gatewayConnectionRepository.clear()
        } catch (expectedError: Exception) {
            failure = expectedError
        }
        try {
            clearConnectionSpecificData(endpoint)
        } catch (expectedError: Exception) {
            failure = failure ?: expectedError
        }
        failure?.let { throw it }
    }
}
