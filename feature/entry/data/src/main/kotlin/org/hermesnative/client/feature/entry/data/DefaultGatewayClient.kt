package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.GatewayContractPort
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest

class DefaultGatewayClient(
    endpoint: String,
    bearerToken: String,
    transport: GatewayTransport = OkHttpGatewayTransport(),
    manifest: GatewayCapabilityManifest = PublicBetaGatewayCapabilityManifest.current,
) : GatewayContractPort by GatewayClientOperations(
        calls = GatewayClientCalls(endpoint, bearerToken, transport),
        manifest = manifest,
    )
