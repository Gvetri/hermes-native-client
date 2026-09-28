package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository

class RemoveGatewayConnection(
    private val gatewayConnectionRepository: GatewayConnectionRepository,
    private val clearConnectionSpecificData: (String?) -> Unit = {},
) {
    fun execute() {
        var failure: Exception? = null
        // Reading the saved endpoint can fail on a corrupt preference. Removal
        // must still discard the credential material and the connection-specific
        // state, so the lookup never gates the cleanup below.
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
