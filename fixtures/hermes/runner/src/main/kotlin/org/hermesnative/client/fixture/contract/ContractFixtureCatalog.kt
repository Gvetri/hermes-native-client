package org.hermesnative.client.fixture.contract

import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest

enum class ContractFixtureFormat {
    JSON,
    SSE,
}

data class ContractFixtureDefinition(
    val path: String,
    val format: ContractFixtureFormat,
)

object ContractFixtureCatalog {
    val definitions =
        listOf(
            ContractFixtureDefinition("capabilities/request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("capabilities/success.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("capabilities/missing-required.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("capabilities/unknown-additive.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("connection/authenticated.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("connection/authentication-failed.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/list-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/list-response-page-1.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/list-response-page-2.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/create-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/create-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/open-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/open-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/history-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/history-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/rename-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/rename-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/delete-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/delete-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/pin-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/pin-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/unpin-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("sessions/unpin-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("runs/create-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("runs/create-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("runs/status-request.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("runs/status-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("runs/observation.sse", ContractFixtureFormat.SSE),
            ContractFixtureDefinition("malformed/invalid-json.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/missing-required-field.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/invalid-required-field-type.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/invalid-sse-framing.sse", ContractFixtureFormat.SSE),
            ContractFixtureDefinition("malformed/mismatched-session-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/mismatched-history-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/mismatched-pin-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/mismatched-run-create-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/mismatched-run-status-response.json", ContractFixtureFormat.JSON),
            ContractFixtureDefinition("malformed/mismatched-run-event.sse", ContractFixtureFormat.SSE),
        )
}

object SupportedGatewayCapabilities {
    val required = PublicBetaGatewayCapabilityManifest.current.requirements.map { it.identifier }
}
