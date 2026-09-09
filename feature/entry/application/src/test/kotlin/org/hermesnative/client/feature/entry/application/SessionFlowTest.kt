package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFlowTest {
    @Test
    fun creating_a_session_forwards_the_optional_title_and_returns_the_gateway_session() {
        val expected = Session(SessionId("created"), "Created", null, pinned = false, updatedAt = "now")
        val gateway = RecordingSessionGateway().apply { createResult = expected }

        val created = CreateSession(gateway).execute("Created")

        assertEquals(expected, created)
        assertEquals(listOf("Created"), gateway.createTitles)
    }

    @Test
    fun loading_the_session_list_requests_the_first_server_page_without_local_paging() {
        val gateway = RecordingSessionGateway()
        val expectedPage = SessionPage(emptyList(), nextCursor = "server-cursor")
        gateway.listResult = expectedPage

        val page = LoadSessionList(gateway).execute()

        assertEquals(expectedPage, page)
        assertEquals(listOf(SessionListRequest()), gateway.listRequests)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun opening_a_session_fetches_the_authoritative_session_then_its_history() {
        val sessionId = SessionId("session-one")
        val session = Session(sessionId, "Real title", "Real preview", pinned = false, updatedAt = "now")
        val history = SessionHistory(sessionId, emptyList(), nextCursor = null)
        val gateway = RecordingSessionGateway(session, history)

        val opened = OpenSession(gateway).execute(sessionId)

        assertEquals(session, opened.session)
        assertEquals(history, opened.history)
        assertEquals(listOf("open", "history"), gateway.operations)
        assertEquals(0, gateway.createCalls)
    }

    private class RecordingSessionGateway(
        private val openedSession: Session? = null,
        private val openedHistory: SessionHistory? = null,
    ) : SessionGatewayPort {
        var listResult = SessionPage(emptyList(), null)
        val listRequests = mutableListOf<SessionListRequest>()
        val operations = mutableListOf<String>()
        var createCalls = 0
        var createResult = Session(SessionId("created"), null, null, pinned = false, updatedAt = null)
        val createTitles = mutableListOf<String?>()

        override fun listSessions(request: SessionListRequest): SessionPage {
            listRequests += request
            return listResult
        }

        override fun createSession(title: String?): Session {
            createCalls += 1
            createTitles += title
            return createResult
        }

        override fun openSession(sessionId: SessionId): Session {
            operations += "open"
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
