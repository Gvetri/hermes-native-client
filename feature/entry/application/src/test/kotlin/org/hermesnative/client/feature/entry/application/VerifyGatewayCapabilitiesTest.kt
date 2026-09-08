package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayCapabilityPort
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifyGatewayCapabilitiesTest {
    @Test
    fun application_consumes_the_typed_capability_port() {
        val expected = GatewayCapabilities(setOf("session.list"))

        val result = VerifyGatewayCapabilities(FakeCapabilityPort(expected)).execute()

        assertEquals(expected, result)
    }

    private class FakeCapabilityPort(
        private val capabilities: GatewayCapabilities,
    ) : GatewayCapabilityPort {
        override fun discoverCapabilities(): GatewayCapabilities = capabilities
    }
}
