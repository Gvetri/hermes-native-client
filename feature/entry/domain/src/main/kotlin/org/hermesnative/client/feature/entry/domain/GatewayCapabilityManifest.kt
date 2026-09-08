package org.hermesnative.client.feature.entry.domain

enum class GatewayContractOperation {
    SESSION_LIST,
    SESSION_CREATE,
    SESSION_OPEN,
    SESSION_HISTORY,
    SESSION_RENAME,
    SESSION_DELETE,
    SESSION_PIN,
    SESSION_UNPIN,
    RUN_CREATE,
    RUN_STATUS,
    RUN_SSE,
}

data class GatewayCapabilityRequirement(
    val identifier: String,
    val operation: GatewayContractOperation,
)

data class GatewayCapabilityManifest(
    val version: Int,
    val discoveryPath: String,
    val requirements: List<GatewayCapabilityRequirement>,
) {
    val requiredIdentifiers: Set<String>
        get() = requirements.mapTo(linkedSetOf()) { it.identifier }
}

object PublicBetaGatewayCapabilityManifest {
    const val VERSION = 1

    val current =
        GatewayCapabilityManifest(
            version = VERSION,
            discoveryPath = "/v1/capabilities",
            requirements =
                listOf(
                    GatewayCapabilityRequirement("session.list", GatewayContractOperation.SESSION_LIST),
                    GatewayCapabilityRequirement("session.create", GatewayContractOperation.SESSION_CREATE),
                    GatewayCapabilityRequirement("session.open", GatewayContractOperation.SESSION_OPEN),
                    GatewayCapabilityRequirement("session.history", GatewayContractOperation.SESSION_HISTORY),
                    GatewayCapabilityRequirement("session.rename", GatewayContractOperation.SESSION_RENAME),
                    GatewayCapabilityRequirement("session.delete", GatewayContractOperation.SESSION_DELETE),
                    GatewayCapabilityRequirement("session.pin", GatewayContractOperation.SESSION_PIN),
                    GatewayCapabilityRequirement("session.unpin", GatewayContractOperation.SESSION_UNPIN),
                    GatewayCapabilityRequirement("run.create", GatewayContractOperation.RUN_CREATE),
                    GatewayCapabilityRequirement("run.status", GatewayContractOperation.RUN_STATUS),
                    GatewayCapabilityRequirement("run.sse", GatewayContractOperation.RUN_SSE),
                ),
        )
}

data class GatewayCapabilities(
    val identifiers: Set<String>,
) {
    fun supports(identifier: String): Boolean = identifier in identifiers
}
