package org.hermesnative.client.feature.entry.wiring

import kotlinx.coroutines.CoroutineScope
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort

data class EntryWiringOverrides(
    val capabilityDiscovery: ((String, String) -> GatewayCapabilities)? = null,
    val sessionGatewayFactory: ((String, String) -> SessionGatewayPort)? = null,
    val runGatewayFactory: ((String, String) -> RunGatewayPort)? = null,
    val coroutineScope: CoroutineScope? = null,
)
