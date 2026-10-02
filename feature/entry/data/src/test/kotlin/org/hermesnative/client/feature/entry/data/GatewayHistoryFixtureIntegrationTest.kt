package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.DeterministicGatewayFixture
import org.hermesnative.client.fixture.FixtureTestContext
import org.hermesnative.client.fixture.GatewayProcessFactory
import org.hermesnative.client.fixture.LocalSyntheticGatewayProcess
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewayMessage
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GatewayHistoryFixtureIntegrationTest {
    private val repositoryRoot =
        File(requireNotNull(System.getProperty("fixture.repositoryRoot")))
    private val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")

    @Test
    fun synthetic_gateway_history_matches_the_pinned_message_projection() {
        val sessionId = "session-1"
        val behavior =
            SyntheticGatewayBehavior(
                initialSessions =
                    listOf(
                        SyntheticGatewaySession(
                            id = sessionId,
                            title = "History",
                            preview = null,
                            pinned = false,
                            history =
                                listOf(
                                    SyntheticGatewayMessage(
                                        id = "message-1",
                                        role = "assistant",
                                        content = "Stable history",
                                        timestamp = "2026-09-08T20:00:00Z",
                                    ),
                                ),
                        ),
                    ),
            )
        fixture(behavior).execute { context ->
            val opened = OpenSession(client(context)).execute(SessionId(sessionId))
            val message = opened.history.messages.single()
            assertEquals(sessionId, opened.history.sessionId.value)
            assertEquals("message-1", message.id)
            assertEquals("assistant", message.role)
            assertEquals("Stable history", message.content)
            assertEquals("2026-09-08T20:00:00Z", message.timestamp)
            // The pinned message projection carries no run metadata.
            assertNull(message.runId)
            assertNull(message.runStatus)
            assertNull(message.runResult)
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
