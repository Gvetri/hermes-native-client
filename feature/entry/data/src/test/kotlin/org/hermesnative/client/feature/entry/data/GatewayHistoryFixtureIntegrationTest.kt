package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.DeterministicGatewayFixture
import org.hermesnative.client.fixture.FixtureTestContext
import org.hermesnative.client.fixture.GatewayProcessFactory
import org.hermesnative.client.fixture.LocalSyntheticGatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewayMessage
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GatewayHistoryFixtureIntegrationTest {
    private val repositoryRoot =
        File(requireNotNull(System.getProperty("fixture.repositoryRoot")))
    private val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")

    @Test
    fun synthetic_gateway_history_preserves_related_run_metadata() {
        val sessionId = "session-1"
        val runId = "run-1"
        val behavior =
            SyntheticGatewayBehavior(
                capabilities = PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers + "client-manifest",
                initialSessions =
                    listOf(
                        SyntheticGatewaySession(
                            id = sessionId,
                            title = "History",
                            preview = null,
                            pinned = false,
                            updatedAt = null,
                            history =
                                listOf(
                                    SyntheticGatewayMessage(
                                        id = "message-1",
                                        role = null,
                                        content = null,
                                        runId = runId,
                                        runStatus = "succeeded",
                                        runResult = null,
                                        timestamp = "2026-09-08T20:00:00Z",
                                    ),
                                ),
                        ),
                    ),
            )
        fixture(behavior).execute { context ->
            val opened = OpenSession(client(context)).execute(SessionId(sessionId))
            val message = opened.history.messages.single()
            assertEquals(runId, message.runId?.value)
            assertEquals("succeeded", message.runStatus)
            assertEquals(null, message.role)
            assertEquals(null, message.content)
            assertEquals(null, message.runResult)
            assertEquals("2026-09-08T20:00:00Z", message.timestamp)
        }
    }

    private fun client(context: FixtureTestContext): DefaultGatewayClient =
        DefaultGatewayClient(
            endpoint = "https://127.0.0.1:${context.endpoint.port}",
            bearerToken = "fixture-only-token",
            transport = LoopbackFixtureTransport(),
        )

    private fun fixture(behavior: SyntheticGatewayBehavior): DeterministicGatewayFixture =
        DeterministicGatewayFixture(
            descriptorFile = descriptorFile,
            processFactory =
                GatewayProcessFactory { descriptor ->
                    LocalSyntheticGatewayProcess.start(descriptor, behavior)
                },
        )
}
