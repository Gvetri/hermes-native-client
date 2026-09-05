package org.hermesnative.client.feature.entry.domain

interface GatewayConnectionRepository {
    fun hasConfiguredConnection(): Boolean
}
