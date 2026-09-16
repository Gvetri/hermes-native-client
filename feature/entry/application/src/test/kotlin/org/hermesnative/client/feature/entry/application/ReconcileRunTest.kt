package org.hermesnative.client.feature.entry.application

import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunReconciliationDecision
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconcileRunTest {
    @Test
    fun fetches_authoritative_run_status_before_session_history() {
        val sessionId = SessionId("session-1")
        val run = Run(RunId("run-1"), sessionId, "succeeded")
        val operations = mutableListOf<String>()
        val runGateway = RecordingRunGateway(run, operations)
        val sessionGateway =
            RecordingSessionGateway(
                SessionHistory(
                    sessionId,
                    listOf(GatewayHistoryMessage("message-1", "assistant", "Done", run.id, "succeeded")),
                    null,
                ),
                operations,
            )

        val result = ReconcileRun(runGateway, sessionGateway).execute(run.id, sessionId)

        assertEquals(run, result.run)
        assertEquals(RunReconciliationDecision.CONFIRMED, result.decision)
        assertEquals(listOf("run-status:run-1", "session-history:session-1"), operations)
    }

    @Test
    fun rejects_a_run_status_for_a_different_session_before_loading_history() {
        val operations = mutableListOf<String>()
        val requestedSessionId = SessionId("session-1")
        val run = Run(RunId("run-1"), SessionId("other-session"), "succeeded")
        val sessionGateway = RecordingSessionGateway(SessionHistory(requestedSessionId, emptyList(), null), operations)

        val error =
            runCatching {
                ReconcileRun(RecordingRunGateway(run, operations), sessionGateway)
                    .execute(run.id, requestedSessionId)
            }.exceptionOrNull()

        assertEquals("INVALID_RESPONSE", (error as? org.hermesnative.client.feature.entry.domain.GatewayException)?.category?.name)
        assertEquals(listOf("run-status:run-1"), operations)
    }

    @Test
    fun rejects_session_history_for_a_different_session() {
        val requestedSessionId = SessionId("session-1")
        val run = Run(RunId("run-1"), requestedSessionId, "succeeded")
        val operations = mutableListOf<String>()
        val mismatchedHistory = SessionHistory(SessionId("other-session"), emptyList(), null)

        val error =
            runCatching {
                ReconcileRun(
                    RecordingRunGateway(run, operations),
                    RecordingSessionGateway(mismatchedHistory, operations),
                ).execute(run.id, requestedSessionId)
            }.exceptionOrNull()

        assertEquals("INVALID_RESPONSE", (error as? org.hermesnative.client.feature.entry.domain.GatewayException)?.category?.name)
        assertEquals(listOf("run-status:run-1", "session-history:session-1"), operations)
    }

    private class RecordingRunGateway(
        private val result: Run,
        private val operations: MutableList<String>,
    ) : RunGatewayPort {
        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run = error("not used")

        override fun getRunStatus(runId: RunId): Run {
            operations += "run-status:${runId.value}"
            return result
        }

        override fun observeRun(runId: RunId): RunEventObservation = error("not used")
    }

    private class RecordingSessionGateway(
        private val history: SessionHistory,
        private val operations: MutableList<String>,
    ) : SessionGatewayPort {
        override fun listSessions(request: SessionListRequest): SessionPage = error("not used")

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = error("not used")

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            operations += "session-history:${sessionId.value}"
            return history
        }

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId) = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")
    }
}

class ReconcileSessionTest {
    @Test
    fun loads_history_then_fetches_status_for_a_run_discovered_after_an_uncertain_send() {
        val sessionId = SessionId("session-1")
        val oldRunId = RunId("run-old")
        val newRunId = RunId("run-new")
        val operations = mutableListOf<String>()
        val history =
            SessionHistory(
                sessionId,
                listOf(
                    GatewayHistoryMessage("old-message", "assistant", "Old", oldRunId, "succeeded"),
                    GatewayHistoryMessage("new-message", "user", "New", newRunId, "running"),
                ),
                null,
            )
        val run = Run(newRunId, sessionId, "running")

        val result =
            ReconcileSession(
                RecordingSessionHistoryGateway(history, operations),
                RecordingStatusGateway(run, operations),
            ).execute(sessionId, setOf(oldRunId))

        assertEquals(history, result.history)
        assertEquals(listOf(run), result.discoveredRuns)
        assertEquals(listOf("session-history:session-1", "run-status:run-new"), operations)
    }

    private class RecordingSessionHistoryGateway(
        private val history: SessionHistory,
        private val operations: MutableList<String>,
    ) : SessionGatewayPort {
        override fun listSessions(request: SessionListRequest): SessionPage = error("not used")

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = error("not used")

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory {
            operations += "session-history:${sessionId.value}"
            return history
        }

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId) = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")
    }

    private class RecordingStatusGateway(
        private val result: Run,
        private val operations: MutableList<String>,
    ) : RunGatewayPort {
        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run = error("not used")

        override fun getRunStatus(runId: RunId): Run {
            operations += "run-status:${runId.value}"
            return result
        }

        override fun observeRun(runId: RunId): RunEventObservation = error("not used")
    }
}
