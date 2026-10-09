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
            } catch (error: Exception) {
                failure = error
                null
            }
        try {
            gatewayConnectionRepository.clear()
        } catch (error: Exception) {
            failure = error
        }
        try {
            clearConnectionSpecificData(endpoint)
        } catch (error: Exception) {
            failure = failure ?: error
        }
        failure?.let { throw it }
    }
}
