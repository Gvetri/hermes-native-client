package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class EntryStateHolderTest {
    @Test
    fun add_gateway_connection_event_requests_connection_setup() {
        val stateHolder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = false),
            )

        stateHolder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)

        assertTrue(stateHolder.uiState.value.connectionSetupRequested)
    }

    @Test
    fun verification_enters_loading_then_connected_and_persists_only_the_endpoint() {
        val repository = FakeGatewayConnectionRepository()
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertFalse(holder.uiState.value.isVerifying)
        assertEquals("https://gateway.example/profile", repository.saved?.endpoint)
        holder.close()
    }

    @Test
    fun recoverable_failure_keeps_inputs_maps_invalid_response_to_a_safe_category_and_can_retry() {
        val repository = FakeGatewayConnectionRepository()
        var attempts = 0
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    attempts += 1
                    if (attempts == 1) {
                        throw GatewayException(GatewayErrorCategory.INVALID_RESPONSE)
                    }
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            )

        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

        assertEquals(EntryErrorCategory.GATEWAY_REQUEST_FAILED, holder.uiState.value.errorCategory)
        assertEquals("https://gateway.example/profile", holder.uiState.value.endpoint)
        assertEquals("memory-only-token", holder.uiState.value.bearerCredential)
        assertFalse(holder.uiState.value.isVerifying)

        holder.onEvent(EntryUiEvent.TryAgainClicked)

        assertTrue(holder.uiState.value.isConnected)
        assertEquals(2, attempts)
        holder.close()
    }

    @Test
    fun successful_connection_loads_the_first_page_with_pinned_sessions_first_and_no_creation() {
        val repository = FakeGatewayConnectionRepository()
        val gateway = FakeSessionGateway()
        val unpinnedOlder = session("unpinned-older", pinned = false)
        val pinnedOlder = session("pinned-older", pinned = true)
        val pinnedNewer = session("pinned-newer", pinned = true)
        val unpinnedNewer = session("unpinned-newer", pinned = false)
        gateway.enqueueList(
            SessionPage(
                sessions = listOf(unpinnedOlder, pinnedOlder, pinnedNewer, unpinnedNewer),
                nextCursor = "ignored-by-this-slice",
            ),
        )
        val holder =
            holder(
                repository = repository,
                verifier = { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
                gateway = gateway,
            )

        verify(holder)

        val sessionList = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(
            listOf("pinned-older", "pinned-newer", "unpinned-older", "unpinned-newer"),
            sessionList.sessions.map { it.id.value },
        )
        assertEquals("Untitled Session", sessionList.sessions.first().title)
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)
        assertTrue(sessionList.showFirstUseGuidance)

        holder.onEvent(EntryUiEvent.CreateSessionClicked)

        assertEquals(0, gateway.createCalls)
        holder.close()
    }

    @Test
    fun refresh_preserves_visible_sessions_when_unavailable_and_reloads_the_first_page_on_retry() {
        val gateway = FakeSessionGateway()
        val firstSession = session("first", title = "First")
        val refreshedSession = session("second", title = "Second")
        gateway.enqueueList(SessionPage(listOf(firstSession), null))
        gateway.enqueueFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
        gateway.enqueueList(SessionPage(listOf(refreshedSession), null))
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val unavailable = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("first"), unavailable.sessions.map { it.id.value })
        assertTrue(unavailable.isStale)
        assertTrue(unavailable.isUnavailable)
        assertEquals(SessionListErrorCategory.GATEWAY_UNAVAILABLE, unavailable.errorCategory)

        holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

        val recovered = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(listOf("second"), recovered.sessions.map { it.id.value })
        assertFalse(recovered.isStale)
        assertFalse(recovered.isUnavailable)
        assertEquals(3, gateway.listRequests.size)
        assertTrue(gateway.listRequests.all { it == SessionListRequest() })
        holder.close()
    }

    @Test
    fun selecting_a_session_opens_authoritative_history_and_a_deleted_session_is_recoverable() {
        val sessionId = SessionId("session-one")
        val gateway = FakeSessionGateway()
        gateway.enqueueList(SessionPage(listOf(session(sessionId.value, "Listed title")), null))
        gateway.openedSession = session(sessionId.value, "Authoritative title")
        gateway.openedHistory =
            SessionHistory(
                sessionId = sessionId,
                messages = listOf(GatewayHistoryMessage("message-one", "user", "Authoritative content")),
                nextCursor = null,
            )
        val holder =
            holder(
                repository = FakeGatewayConnectionRepository(),
                verifier = { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
                gateway = gateway,
            )

        verify(holder)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
        assertEquals("Authoritative title", opened.session.title)
        assertEquals(listOf("Authoritative content"), opened.messages.map { it.content })
        assertEquals(listOf("open", "history"), gateway.operations)

        gateway.openFailure = GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED)
        holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))

        val unavailable = requireNotNull(holder.uiState.value.sessionList)
        assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, unavailable.errorCategory)
        assertTrue(unavailable.isUnavailable)
        assertEquals(listOf("session-one"), unavailable.sessions.map { it.id.value })
        holder.close()
    }

    private fun holder(
        repository: FakeGatewayConnectionRepository,
        verifier: (String, String) -> GatewayCapabilities,
        gateway: SessionGatewayPort? = null,
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection = VerifyGatewayConnection(repository, discoverCapabilities = verifier),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionGatewayFactory =
                gateway?.let { sessionGateway ->
                    { _, _ -> sessionGateway }
                },
        )

    private fun verify(holder: EntryStateHolder) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
    }

    private fun session(
        id: String,
        title: String? = null,
        pinned: Boolean = false,
    ): Session =
        Session(
            id = SessionId(id),
            title = title,
            preview = "Server preview",
            pinned = pinned,
            updatedAt = "server-time",
        )

    private class FakeGatewayConnectionRepository : GatewayConnectionRepository {
        var saved: GatewayConnection? = null

        override fun load(): GatewayConnection? = saved

        override fun save(connection: GatewayConnection) {
            saved = connection
        }
    }

    private class FakeSessionGateway : SessionGatewayPort {
        private val listResults = ArrayDeque<Result<SessionPage>>()
        val listRequests = mutableListOf<SessionListRequest>()
        val operations = mutableListOf<String>()
        var openedSession: Session? = null
        var openedHistory: SessionHistory? = null
        var openFailure: GatewayException? = null
        var createCalls = 0

        fun enqueueList(page: SessionPage) {
            listResults += Result.success(page)
        }

        fun enqueueFailure(error: GatewayException) {
            listResults += Result.failure(error)
        }

        override fun listSessions(request: SessionListRequest): SessionPage {
            listRequests += request
            return listResults.removeFirst().getOrThrow()
        }

        override fun createSession(title: String?): Session {
            createCalls += 1
            error("Session creation is outside this issue")
        }

        override fun openSession(sessionId: SessionId): Session {
            operations += "open"
            openFailure?.let { throw it }
            return requireNotNull(openedSession)
        }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            operations += "history"
            return requireNotNull(openedHistory)
        }

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId): Unit = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")
    }
}
