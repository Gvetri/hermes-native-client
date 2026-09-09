package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.application.LoadSessionList
import org.hermesnative.client.feature.entry.application.OpenSession
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GatewaySessionFixtureIntegrationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )
    private val descriptorFile = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")
    private val requiredCapabilities = PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers

    @Test
    fun real_client_lists_ordered_sessions_and_keeps_open_history_isolated() {
        val behavior =
            behavior(
                listOf(
                    session(PINNED_A, "Pinned A", "Pinned A preview", pinned = true, history = "History A"),
                    session(PINNED_B, "Pinned B", "Pinned B preview", pinned = true, history = "History B"),
                    session(SERVER_A, "Server A", "Server A preview", pinned = false, history = "History C"),
                    session(SERVER_B, "Server B", "Server B preview", pinned = false, history = "History D"),
                ),
            )

        fixture(behavior).execute { context ->
            assertEquals("127.0.0.1", context.endpoint.host)
            assertEquals("http", context.endpoint.scheme)
            val client = client(context)
            behavior.requests.clear()

            client.discoverCapabilities()
            val page = LoadSessionList(client).execute()

            assertEquals(
                listOf(PINNED_A, PINNED_B, SERVER_A, SERVER_B),
                page.sessions.map { it.id.value },
            )
            assertEquals("Pinned A", page.sessions.first().title)
            assertEquals("Pinned A preview", page.sessions.first().preview)
            assertEquals(listOf(true, true, false, false), page.sessions.map { it.pinned })
            assertEquals(
                listOf("/v1/capabilities", "/v1/sessions"),
                behavior.requests.map { it.path },
            )
            assertTrue(behavior.requests.none { it.method == "POST" })
            assertTrue(behavior.requests.none { it.path.endsWith("/history") })
            assertTrue(behavior.requests.none { it.path.count { character -> character == '/' } > 2 })
            assertNoCredentials(behavior)

            val openedA = OpenSession(client).execute(SessionId(PINNED_A))
            val openedB = OpenSession(client).execute(SessionId(PINNED_B))

            assertEquals(PINNED_A, openedA.session.id.value)
            assertEquals("History A", openedA.history.messages.single().content)
            assertEquals(PINNED_B, openedB.session.id.value)
            assertEquals("History B", openedB.history.messages.single().content)
            assertNotEquals(openedA.history.messages.single().content, openedB.history.messages.single().content)
            assertNoCredentials(behavior)
        }
    }

    @Test
    fun refresh_reads_the_first_page_updates_fixture_metadata_and_is_repeat_safe() {
        val behavior = behavior(listOf(session(SERVER_A, "Initial title", "Initial preview", pinned = false)))

        fixture(behavior).execute { context ->
            val client = client(context)
            behavior.requests.clear()

            val initial = LoadSessionList(client).execute()
            behavior.sessions.clear()
            behavior.sessions += session(SERVER_A, "Updated title", "Updated preview", pinned = false)
            val refreshed = LoadSessionList(client).execute()
            val repeated = LoadSessionList(client).execute()

            assertEquals("Initial title", initial.sessions.single().title)
            assertEquals("Updated title", refreshed.sessions.single().title)
            assertEquals("Updated preview", refreshed.sessions.single().preview)
            assertEquals(refreshed, repeated)
            assertEquals(3, behavior.requests.count { it.path == "/v1/sessions" })
            assertEquals(
                listOf("limit=20", "limit=20", "limit=20"),
                behavior.requests.filter { it.path == "/v1/sessions" }.map { it.query },
            )
            assertTrue(behavior.requests.all { it.path == "/v1/sessions" })
            assertTrue(behavior.requests.none { it.method == "POST" })
            assertNoCredentials(behavior)
        }
    }

    @Test
    fun empty_and_populated_lists_never_create_a_session_automatically() {
        val emptyBehavior = behavior(emptyList())
        fixture(emptyBehavior).execute { context ->
            val client = client(context)
            emptyBehavior.requests.clear()

            assertTrue(LoadSessionList(client).execute().sessions.isEmpty())
            assertTrue(emptyBehavior.requests.none { it.method == "POST" })
            assertNoCredentials(emptyBehavior)
        }

        val populatedBehavior = behavior(listOf(session(SERVER_A, "Existing", "Existing preview", pinned = false)))
        fixture(populatedBehavior).execute { context ->
            val client = client(context)
            populatedBehavior.requests.clear()

            assertFalse(LoadSessionList(client).execute().sessions.isEmpty())
            assertTrue(populatedBehavior.requests.none { it.method == "POST" })
            assertNoCredentials(populatedBehavior)
        }
    }

    @Test
    fun missing_or_deleted_session_returns_a_safe_recoverable_gateway_failure() {
        val behavior = behavior(listOf(session(SERVER_A, "Deletable", "Preview", pinned = false)))

        fixture(behavior).execute { context ->
            val client = client(context)
            LoadSessionList(client).execute()
            behavior.requests.clear()
            behavior.sessions.clear()

            val error =
                try {
                    OpenSession(client).execute(SessionId(SERVER_A))
                    error("Expected a deleted Session to fail safely.")
                } catch (failure: GatewayException) {
                    failure
                }

            assertEquals(GatewayErrorCategory.GATEWAY_REQUEST_FAILED, error.category)
            assertEquals(listOf("/v1/sessions/$SERVER_A"), behavior.requests.map { it.path })
            assertTrue(behavior.requests.none { it.path.endsWith("/history") })
            assertTrue(behavior.requests.none { it.method == "POST" })
            assertNoCredentials(behavior)
        }
    }

    @Test
    fun explicit_creation_is_one_remote_mutation_and_refresh_failure_does_not_create_again() {
        val behavior = behavior(emptyList())

        fixture(behavior).execute { context ->
            val client = client(context)
            behavior.requests.clear()

            assertTrue(LoadSessionList(client).execute().sessions.isEmpty())
            assertTrue(behavior.requests.none { it.method == "POST" })

            val created = client.createSession(null)
            assertEquals(CREATED_SESSION, created.id.value)
            assertNull(created.title)
            assertEquals(1, behavior.requests.count { it.method == "POST" && it.path == "/v1/sessions" })

            val opened = OpenSession(client).execute(created.id)
            assertEquals(CREATED_SESSION, opened.session.id.value)
            assertEquals("Created history", opened.history.messages.single().content)

            behavior.failNextSessionList = true
            val refreshFailure =
                try {
                    LoadSessionList(client).execute()
                    error("Expected the synthetic refresh failure.")
                } catch (failure: GatewayException) {
                    failure
                }
            assertEquals(GatewayErrorCategory.GATEWAY_REQUEST_FAILED, refreshFailure.category)
            assertEquals(1, behavior.requests.count { it.method == "POST" && it.path == "/v1/sessions" })
            assertNoCredentials(behavior)
        }
    }

    private fun client(context: FixtureTestContext): DefaultGatewayClient =
        DefaultGatewayClient(
            endpoint = "https://127.0.0.1:${context.endpoint.port}",
            bearerToken = "fixture-only-token",
            transport = LoopbackFixtureTransport(),
        )

    private fun assertNoCredentials(behavior: SyntheticGatewayBehavior) {
        assertTrue(behavior.requests.none { it.hasAuthorizationHeader })
    }

    private class LoopbackFixtureTransport : GatewayTransport {
        private val delegate = OkHttpGatewayTransport()

        override fun execute(request: GatewayHttpRequest): GatewayHttpResponse = delegate.execute(toLoopbackRequest(request))

        override fun openEventStream(request: GatewayHttpRequest): GatewayEventStream = delegate.openEventStream(toLoopbackRequest(request))

        private fun toLoopbackRequest(request: GatewayHttpRequest): GatewayHttpRequest {
            val secureUrl = request.url.removePrefix("https://")
            require(secureUrl.startsWith("127.0.0.1:")) {
                "Fixture transport accepts only the loopback fixture endpoint."
            }
            return GatewayHttpRequest(
                method = request.method,
                url = "http://$secureUrl",
                headers = request.headers.filterKeys { it != "Authorization" },
                body = request.body,
            )
        }
    }

    private fun behavior(sessions: List<SyntheticGatewaySession>): SyntheticGatewayBehavior =
        SyntheticGatewayBehavior(
            capabilities = requiredCapabilities + "client-manifest",
            initialSessions = sessions,
        )

    private fun fixture(behavior: SyntheticGatewayBehavior): DeterministicGatewayFixture =
        DeterministicGatewayFixture(
            descriptorFile = descriptorFile,
            processFactory =
                GatewayProcessFactory { descriptor ->
                    LocalSyntheticGatewayProcess.start(descriptor, behavior)
                },
        )

    private fun session(
        id: String,
        title: String,
        preview: String,
        pinned: Boolean,
        history: String? = null,
    ): SyntheticGatewaySession =
        SyntheticGatewaySession(
            id = id,
            title = title,
            preview = preview,
            pinned = pinned,
            updatedAt = "2026-09-08T20:00:00Z",
            history =
                history?.let {
                    listOf(
                        SyntheticGatewayMessage(
                            id = "message-$id",
                            role = "assistant",
                            content = it,
                        ),
                    )
                }.orEmpty(),
        )

    private companion object {
        const val PINNED_A = "11111111-1111-4111-8111-111111111111"
        const val PINNED_B = "22222222-2222-4222-8222-222222222222"
        const val SERVER_A = "33333333-3333-4333-8333-333333333333"
        const val SERVER_B = "44444444-4444-4444-8444-444444444444"
        const val CREATED_SESSION = "55555555-5555-4555-8555-555555555555"
    }
}
