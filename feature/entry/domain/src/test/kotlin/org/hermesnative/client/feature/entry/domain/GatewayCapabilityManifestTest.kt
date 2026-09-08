package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GatewayCapabilityManifestTest {
    @Test
    fun public_beta_manifest_is_versioned_and_maps_every_required_identifier() {
        val manifest = PublicBetaGatewayCapabilityManifest.current

        assertEquals(1, manifest.version)
        assertEquals("/v1/capabilities", manifest.discoveryPath)
        assertEquals(
            listOf(
                "session.list",
                "session.create",
                "session.open",
                "session.history",
                "session.rename",
                "session.delete",
                "session.pin",
                "session.unpin",
                "run.create",
                "run.status",
                "run.sse",
            ),
            manifest.requirements.map { it.identifier },
        )
        assertEquals(manifest.requirements.size, manifest.requiredIdentifiers.size)
        assertSame(GatewayContractOperation.SESSION_LIST, manifest.requirements.first().operation)
        assertSame(GatewayContractOperation.RUN_SSE, manifest.requirements.last().operation)
    }
}
