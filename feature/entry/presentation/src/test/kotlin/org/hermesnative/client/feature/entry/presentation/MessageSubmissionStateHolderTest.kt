package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.GatewayErrorCategory
import org.hermesnative.client.feature.entry.domain.GatewayException
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.Session
import org.hermesnative.client.feature.entry.domain.SessionGatewayPort
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.domain.SessionListRequest
import org.hermesnative.client.feature.entry.domain.SessionPage
import org.hermesnative.client.feature.entry.domain.SessionPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TEST_TIMEOUT_MILLIS = 5_000L

class MessageSubmissionStateHolderTest {
    @Test
    fun successful_send_creates_one_run_clears_only_the_submitted_draft_and_stores_the_returned_identity() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway = FakeGateway(listOf(session)).apply { enqueueRun(run) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(session.id to "Run this"), gateway.runRequests)
            assertEquals(run, opened.latestRun)
            assertEquals("", opened.composerText)
            assertFalse(opened.isSending)
            assertNull(opened.sendErrorCategory)
        } finally {
            holder.close()
        }
    }

    @Test
    fun an_active_run_blocks_send_but_keeps_the_next_draft_editable() {
        val session = session("session-1")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories =
                    mapOf(
                        session.id to
                            SessionHistory(
                                session.id,
                                listOf(GatewayHistoryMessage("run-message", "user", "Run this", RunId("run-1"), "running")),
                                null,
                            ),
                    ),
            )
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not accept this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals("Do not accept this", opened.composerText)
            assertEquals(emptyList<Pair<SessionId, String>>(), gateway.runRequests)
            assertTrue(opened.latestRun?.status == "running")
        } finally {
            holder.close()
        }
    }

    @Test
    fun duplicate_send_is_ignored_while_the_first_run_request_is_pending() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(listOf(session)).apply {
                enqueueRun(run)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run once"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SendMessageClicked)

            assertEquals(listOf(session.id to "Run once"), gateway.runRequests)
            assertTrue(requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).isSending)
            gateway.releaseRun.countDown()
            awaitState(holder) { it.sessionList?.openedSession?.isSending == false }
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun reopening_a_pending_submission_preserves_a_changed_draft_and_blocks_duplicate_send() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(listOf(session)).apply {
                enqueueRun(run)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run once"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) { it.sessionList?.openedSession?.isSending == true }
            assertEquals(
                "Run once",
                requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession).composerText,
            )

            holder.onEvent(EntryUiEvent.ComposerTextChanged("New draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(listOf(session.id to "Run once"), gateway.runRequests)

            gateway.releaseRun.countDown()
            awaitState(holder) { it.sessionList?.openedSession?.isSending == false }
            val completed = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals("New draft", completed.composerText)
            assertEquals(run, completed.latestRun)
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun an_older_active_run_blocks_submission_when_the_latest_run_is_terminal() {
        val session = session("session-1")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories =
                    mapOf(
                        session.id to
                            SessionHistory(
                                session.id,
                                listOf(
                                    GatewayHistoryMessage(
                                        "run-1-message",
                                        "user",
                                        "First",
                                        RunId("run-1"),
                                        "running",
                                    ),
                                    GatewayHistoryMessage(
                                        "run-2-message",
                                        "user",
                                        "Second",
                                        RunId("run-2"),
                                        "succeeded",
                                    ),
                                ),
                                null,
                            ),
                    ),
            )
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not send"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(opened.activeRuns.any { it.id == RunId("run-1") })
            assertEquals(emptyList<Pair<SessionId, String>>(), gateway.runRequests)
        } finally {
            holder.close()
        }
    }

    @Test
    fun a_returned_active_run_is_retained_when_reopened_history_omits_it() {
        val session = session("session-1")
        val returnedRun = Run(RunId("run-new"), session.id, "starting")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories =
                    mapOf(
                        session.id to
                            SessionHistory(
                                session.id,
                                listOf(
                                    GatewayHistoryMessage(
                                        "old-run-message",
                                        "user",
                                        "Previous",
                                        RunId("run-old"),
                                        "succeeded",
                                    ),
                                ),
                                null,
                            ),
                    ),
            ).apply { enqueueRun(returnedRun) }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Create active run"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) { it.sessionList?.openedSession?.latestRun?.id == returnedRun.id }

            val reopened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertTrue(reopened.activeRuns.any { it.id == returnedRun.id })
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not send twice"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(listOf(session.id to "Create active run"), gateway.runRequests)
        } finally {
            holder.close()
        }
    }

    @Test
    fun repeated_history_statuses_use_the_latest_status_for_one_run() {
        val session = session("session-1")
        val gateway =
            FakeGateway(
                sessions = listOf(session),
                histories =
                    mapOf(
                        session.id to
                            SessionHistory(
                                session.id,
                                listOf(
                                    GatewayHistoryMessage(
                                        "run-message-started",
                                        "user",
                                        "Run",
                                        RunId("run-1"),
                                        "running",
                                    ),
                                    GatewayHistoryMessage(
                                        "run-message-completed",
                                        "assistant",
                                        "Done",
                                        RunId("run-1"),
                                        "succeeded",
                                    ),
                                ),
                                null,
                            ),
                    ),
            )
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals("succeeded", opened.latestRun?.status)
            assertTrue(opened.activeRuns.isEmpty())
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Send after completion"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(listOf(session.id to "Send after completion"), gateway.runRequests)
        } finally {
            holder.close()
        }
    }

    @Test
    fun late_run_completion_after_removal_cannot_pollute_a_reconnected_gateway() {
        val session = session("session-1")
        val oldRun = Run(RunId("run-old"), session.id, "starting")
        val newRun = Run(RunId("run-new"), session.id, "starting")
        val gateway =
            FakeGateway(listOf(session)).apply {
                enqueueRun(oldRun)
                enqueueRun(newRun)
                blockRunCreation = true
            }
        val holder = holder(gateway, Dispatchers.Default)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Old request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.runStarted.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            gateway.blockRunCreation = false
            gateway.releaseRun.countDown()
            assertTrue(gateway.runFinished.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            Thread.sleep(100)

            connect(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            awaitState(holder) { it.sessionList?.openedSession?.session?.id == session.id }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("New request"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(holder) { it.sessionList?.openedSession?.isSending == false }

            assertEquals(
                listOf(session.id to "Old request", session.id to "New request"),
                gateway.runRequests,
            )
        } finally {
            gateway.releaseRun.countDown()
            holder.close()
        }
    }

    @Test
    fun an_active_run_in_one_session_does_not_disable_a_different_session() {
        val first = session("session-1")
        val second = session("session-2")
        val firstRun = Run(RunId("run-1"), first.id, "starting")
        val secondRun = Run(RunId("run-2"), second.id, "starting")
        val gateway =
            FakeGateway(listOf(first, second)).apply {
                enqueueRun(firstRun)
                enqueueRun(secondRun)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, first.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("First"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(second.id))
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Second"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            assertEquals(
                listOf(first.id to "First", second.id to "Second"),
                gateway.runRequests,
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun confirmed_failure_preserves_the_draft_and_only_an_explicit_retry_submits_again() {
        val session = session("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            FakeGateway(listOf(session)).apply {
                enqueueRunFailure(GatewayException(GatewayErrorCategory.GATEWAY_REQUEST_FAILED))
                enqueueRun(run)
            }
        val holder = holder(gateway)

        try {
            open(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Keep this draft"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val failed = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals("Keep this draft", failed.composerText)
            assertEquals(MessageSendErrorCategory.GATEWAY_REQUEST_FAILED, failed.sendErrorCategory)
            assertFalse(failed.isSending)
            assertEquals(1, gateway.runRequests.size)

            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val retried = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(2, gateway.runRequests.size)
            assertEquals("", retried.composerText)
            assertEquals(run, retried.latestRun)
        } finally {
            holder.close()
        }
    }

    @Test
    fun removing_the_gateway_clears_persisted_connection_and_in_memory_drafts() {
        val session = session("session-1")
        val dataSource = InMemoryGatewayConnectionDataSource()
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val gateway = FakeGateway(listOf(session))
        val holder = holder(gateway, repository = repository)

        try {
            connect(holder, gateway)
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Forget me"))
            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)

            assertNull(repository.load())
            assertFalse(holder.uiState.value.isConnected)
            assertNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    private fun holder(
        gateway: FakeGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        repository: GatewayConnectionRepository = DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource()),
    ): EntryStateHolder =
        EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
        )

    private fun connect(
        holder: EntryStateHolder,
        gateway: FakeGateway,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) {
            it.sessionList?.sessions ==
                gateway.sessions.map { session -> session.toSessionItemUiState() }
        }
    }

    private fun open(
        holder: EntryStateHolder,
        gateway: FakeGateway,
        sessionId: SessionId,
    ) {
        connect(holder, gateway)
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
    }

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (EntryUiState) -> Boolean,
    ) {
        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                holder.uiState.first(predicate)
            }
        }
    }

    private fun session(id: String): Session =
        Session(
            id = SessionId(id),
            title = "Session $id",
            preview = "Preview",
            pinned = false,
            updatedAt = null,
        )

    private class FakeGateway(
        val sessions: List<Session>,
        private val histories: Map<SessionId, SessionHistory> = emptyMap(),
    ) : SessionGatewayPort, RunGatewayPort {
        val runRequests = mutableListOf<Pair<SessionId, String>>()
        private val runResults = ArrayDeque<Result<Run>>()
        val runStarted = CountDownLatch(1)
        val runFinished = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        var blockRunCreation = false

        fun enqueueRun(run: Run) {
            runResults += Result.success(run)
        }

        fun enqueueRunFailure(error: GatewayException) {
            runResults += Result.failure(error)
        }

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(sessions, null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = sessions.single { it.id == sessionId }

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            histories[sessionId] ?: SessionHistory(sessionId, emptyList(), null)

        override fun renameSession(
            sessionId: SessionId,
            title: String,
        ): Session = error("not used")

        override fun deleteSession(sessionId: SessionId): Unit = error("not used")

        override fun pinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun unpinSession(sessionId: SessionId): SessionPinResult = error("not used")

        override fun createRun(
            sessionId: SessionId,
            input: String,
        ): Run {
            runRequests += sessionId to input
            if (blockRunCreation) {
                runStarted.countDown()
                check(releaseRun.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "Timed out waiting for Run release." }
            }
            return runResults.removeFirst().getOrThrow().also { runFinished.countDown() }
        }

        override fun getRunStatus(runId: RunId): Run = error("not used")

        override fun observeRun(runId: RunId) = error("not used")
    }
}
