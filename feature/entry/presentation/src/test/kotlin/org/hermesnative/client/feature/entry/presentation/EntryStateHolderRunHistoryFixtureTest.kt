package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.fixture.SyntheticGatewayBehavior
import org.hermesnative.client.fixture.SyntheticGatewayMessage
import org.hermesnative.client.fixture.SyntheticGatewaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryStateHolderRunHistoryFixtureTest {
    @Test
    fun refresh_replaces_real_gateway_metadata_preserves_only_an_unsent_draft_and_recovers_from_remote_deletion() {
        val targetId = SessionId(SERVER_A)
        val behavior =
            SyntheticGatewayBehavior(
                initialSessions =
                    listOf(
                        fixtureSession(SERVER_A, "Listed title", pinned = false),
                        fixtureSession(SERVER_B, "Removed on refresh", pinned = false),
                    ),
            )

        fixture(behavior).execute { context ->
            val holder = stateHolder(client(context))
            try {
                connect(holder)
                holder.onEvent(EntryUiEvent.RenameSessionClicked(targetId))
                holder.onEvent(EntryUiEvent.RenameSessionTitleChanged(targetId, "Unsent draft"))

                behavior.sessions.clear()
                behavior.sessions +=
                    fixtureSession(SERVER_A, "Server replacement", pinned = true, preview = "Server preview")
                holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

                val refreshed = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(listOf(SERVER_A), refreshed.sessions.map { it.id.value })
                assertEquals("Server replacement", refreshed.sessions.single().title)
                assertEquals("Server preview", refreshed.sessions.single().preview)
                assertTrue(refreshed.sessions.single().pinned)
                assertEquals("Unsent draft", refreshed.sessionMutations[targetId]?.rename?.titleDraft)
                assertFalse(refreshed.isStale)
                assertFalse(refreshed.isUnavailable)

                behavior.sessions.clear()
                holder.onEvent(EntryUiEvent.SessionClicked(targetId))
                val deleted = requireNotNull(holder.uiState.value.sessionList)
                assertEquals(SessionListErrorCategory.SESSION_UNAVAILABLE, deleted.errorCategory)
                assertTrue(deleted.isUnavailable)
                assertEquals(listOf(SERVER_A), deleted.sessions.map { it.id.value })
                assertEquals("Unsent draft", deleted.sessionMutations[targetId]?.rename?.titleDraft)

                holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
                val recovered = requireNotNull(holder.uiState.value.sessionList)
                assertTrue(recovered.sessions.isEmpty())
                assertTrue(recovered.sessionMutations.isEmpty())
                assertFalse(recovered.isStale)
                assertFalse(recovered.isUnavailable)
                assertEquals(null, recovered.errorCategory)
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun restart_reconciles_a_known_local_run_through_the_gateway_fixture() {
        val scenario = restartScenario()

        fixture(scenario.behavior).execute { context ->
            stateHolder(
                client(context),
                runGateway = scenario.runGateway,
                recoveryRegistry = scenario.recoveryRegistry,
            ).close()
            val holder =
                stateHolder(
                    client(context),
                    runGateway = scenario.runGateway,
                    recoveryRegistry = scenario.recoveryRegistry,
                )
            try {
                assertEquals(
                    listOf(RunRecoveryEntry(scenario.session, scenario.localRun.id)),
                    scenario.recoveryRegistry.load(),
                )
                connect(holder)
                awaitFixtureState(holder) { state ->
                    state.sessionList?.sessions?.singleOrNull()?.id == scenario.session &&
                        scenario.runGateway.statusRequests == listOf(scenario.localRun.id) &&
                        scenario.recoveryRegistry.load().isEmpty()
                }

                holder.onEvent(EntryUiEvent.SessionClicked(scenario.session))
                awaitFixtureState(holder) { state ->
                    val opened = state.sessionList?.openedSession
                    opened?.latestRun?.id == scenario.localRun.id &&
                        opened.latestRunState == RunPresentationState.SUCCEEDED &&
                        !opened.isReconciliationInProgress &&
                        scenario.runGateway.statusRequests == listOf(scenario.localRun.id, scenario.localRun.id)
                }
                assertTrue(scenario.recoveryRegistry.load().isEmpty())
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun history_without_run_metadata_never_invents_runs_or_local_recovery_entries() {
        val scenario = externalRunsScenario()
        val session = SessionId(scenario.sessionId)

        fixture(scenario.behavior).execute { context ->
            val holder = stateHolder(client(context), runGateway = scenario.runGateway)
            try {
                connect(holder)
                holder.onEvent(EntryUiEvent.SessionClicked(session))
                awaitFixtureState(holder) { state ->
                    val opened = state.sessionList?.openedSession
                    opened?.messages?.map { it.id } ==
                        listOf("external-message-failed", "external-message-succeeded") &&
                        !opened.isRefreshing
                }

                val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
                assertEquals(listOf("Remote failure", "Remote result"), opened.messages.map { it.content })
                assertTrue(opened.messages.all { it.runId == null && it.runStatus == null && it.runResult == null })
                assertEquals(null, opened.latestRun)
                assertTrue(scenario.runGateway.statusRequests.isEmpty())
                assertTrue(sessionRunsTrackedBy(holder, session).isEmpty())

                scenario.behavior.requests.clear()
                holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
                awaitFixtureState(holder) { state ->
                    val refreshed = state.sessionList?.openedSession
                    refreshed?.messages?.map { it.id } ==
                        listOf("external-message-failed", "external-message-succeeded") &&
                        !refreshed.isRefreshing
                }

                val refreshed = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
                assertTrue(refreshed.messages.all { it.runId == null && it.runStatus == null })
                assertTrue(scenario.runGateway.statusRequests.isEmpty())
                assertTrue(sessionRunsTrackedBy(holder, session).isEmpty())
            } finally {
                holder.close()
            }
        }
    }

    @Test
    fun a_created_run_stays_locally_tracked_after_history_refresh_without_inventing_history_runs() {
        val scenario = createdRunScenario()

        fixture(scenario.behavior).execute { context ->
            val client = client(context)
            val holder = stateHolder(client, runGateway = scenario.runGateway)
            try {
                connect(holder)
                holder.onEvent(EntryUiEvent.SessionClicked(scenario.session))
                awaitFixtureState(holder) { state ->
                    val opened = state.sessionList?.openedSession
                    opened?.messages?.map { it.id } ==
                        listOf("external-message-failed", "external-message-succeeded") &&
                        !opened.isRefreshing
                }
                assertTrue(sessionRunsTrackedBy(holder, scenario.session).isEmpty())

                sendLocalRequestAndAwaitTracking(holder, scenario)

                assertEquals(listOf(scenario.session to "Local request"), scenario.runGateway.createdRunRequests)
                val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
                assertTrue(opened.messages.all { it.runId == null && it.runStatus == null && it.runResult == null })
                assertEquals(
                    listOf(
                        "2026-09-08T19:00:00Z",
                        "2026-09-08T20:00:00Z",
                        "2026-09-08T21:00:00Z",
                        "2026-09-08T22:00:00Z",
                    ),
                    opened.messages.map { it.timestamp?.toString() },
                )
                assertEquals(listOf(scenario.localRun.id), sessionRunsTrackedBy(holder, scenario.session).map { it.id })

                assertRefreshedRunStaysTracked(holder, scenario)
            } finally {
                holder.close()
            }
        }
    }
}

internal fun restartScenario(): RestartScenario {
    val sessionId = "recovery-session"
    val session = SessionId(sessionId)
    val localRun = Run(RunId("local-recovered"), session, "running")
    val recoveredRun = localRun.copy(status = "succeeded")
    val history =
        listOf(
            historyMessage("external-result", "assistant", "External result", "2026-09-08T20:00:00Z"),
            historyMessage("local-result", "assistant", "Recovered result", "2026-09-08T21:00:00Z"),
        )
    val behavior =
        SyntheticGatewayBehavior(
            initialSessions =
                listOf(
                    SyntheticGatewaySession(
                        id = sessionId,
                        title = "Recovery",
                        preview = null,
                        pinned = false,
                        history = history,
                    ),
                ),
        )
    val runGateway =
        FixtureRunGateway(
            statuses = mapOf(localRun.id to recoveredRun),
        )
    return RestartScenario(
        session = session,
        localRun = localRun,
        behavior = behavior,
        runGateway = runGateway,
        recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(RunRecoveryEntry(session, localRun.id))),
    )
}

internal fun externalRunsScenario(): ExternalRunsScenario {
    val sessionId = "external-session"
    val history =
        listOf(
            historyMessage("external-message-failed", "assistant", "Remote failure", "2026-09-08T20:00:00Z"),
            historyMessage("external-message-succeeded", "assistant", "Remote result", "2026-09-08T21:00:00Z"),
        )
    val behavior =
        SyntheticGatewayBehavior(
            initialSessions =
                listOf(
                    SyntheticGatewaySession(
                        id = sessionId,
                        title = "External runs",
                        preview = null,
                        pinned = false,
                        history = history,
                    ),
                ),
        )
    return ExternalRunsScenario(
        sessionId = sessionId,
        behavior = behavior,
        runGateway = FixtureRunGateway(statuses = emptyMap()),
    )
}

internal fun createdRunScenario(): CreatedRunScenario {
    val sessionId = "7c4d3b20-7c7a-4e2a-a593-3a11c2e93f70"
    val session = SessionId(sessionId)
    val localRun = Run(RunId("local-run-created"), session, "succeeded")
    val externalHistory =
        listOf(
            historyMessage("external-message-failed", "assistant", "Remote failure", "2026-09-08T20:00:00Z"),
            historyMessage("external-message-succeeded", "assistant", "Remote result", "2026-09-08T21:00:00Z"),
        )
    val mixedHistory =
        listOf(
            historyMessage("local-message-user", "user", "Local request", "2026-09-08T19:00:00Z"),
        ) + externalHistory +
            listOf(
                historyMessage("local-message-result", "assistant", "Local result", "2026-09-08T22:00:00Z"),
            )
    val behavior =
        SyntheticGatewayBehavior(
            initialSessions =
                listOf(
                    SyntheticGatewaySession(
                        id = sessionId,
                        title = "Mixed runs",
                        preview = null,
                        pinned = false,
                        history = externalHistory,
                    ),
                ),
        )
    val runGateway =
        FixtureRunGateway(
            statuses = mapOf(localRun.id to localRun),
            createdRun = localRun,
        )
    return CreatedRunScenario(
        session = session,
        localRun = localRun,
        mixedHistory = mixedHistory,
        behavior = behavior,
        runGateway = runGateway,
    )
}

internal fun sendLocalRequestAndAwaitTracking(
    holder: EntryStateHolder,
    scenario: CreatedRunScenario,
) {
    scenario.behavior.sessions[0] = scenario.behavior.sessions.single().copy(history = scenario.mixedHistory)
    holder.onEvent(EntryUiEvent.ComposerTextChanged("Local request"))
    holder.onEvent(EntryUiEvent.SendMessageClicked)
    awaitFixtureState(holder) { state ->
        val opened = state.sessionList?.openedSession
        opened?.messages?.map { it.id } ==
            listOf(
                "local-message-user",
                "external-message-failed",
                "external-message-succeeded",
                "local-message-result",
            ) &&
            opened.latestRun?.id == scenario.localRun.id &&
            opened.latestRunState == RunPresentationState.SUCCEEDED &&
            opened.sendErrorCategory == null &&
            !opened.hasUnresolvedSubmission &&
            !opened.isSending
    }
}

internal fun assertRefreshedRunStaysTracked(
    holder: EntryStateHolder,
    scenario: CreatedRunScenario,
) {
    holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
    awaitFixtureState(holder) { state ->
        val refreshed = state.sessionList?.openedSession
        refreshed?.messages?.map { it.id } ==
            listOf(
                "local-message-user",
                "external-message-failed",
                "external-message-succeeded",
                "local-message-result",
            ) &&
            refreshed.latestRun?.id == scenario.localRun.id &&
            !refreshed.isRefreshing
    }
    assertEquals(listOf(scenario.localRun.id), sessionRunsTrackedBy(holder, scenario.session).map { it.id })
}

internal fun historyMessage(
    id: String,
    role: String,
    content: String,
    timestamp: String,
): SyntheticGatewayMessage =
    SyntheticGatewayMessage(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
    )

internal data class RestartScenario(
    val session: SessionId,
    val localRun: Run,
    val behavior: SyntheticGatewayBehavior,
    val runGateway: FixtureRunGateway,
    val recoveryRegistry: InMemoryRunRecoveryRegistry,
)

internal data class ExternalRunsScenario(
    val sessionId: String,
    val behavior: SyntheticGatewayBehavior,
    val runGateway: FixtureRunGateway,
)

internal data class CreatedRunScenario(
    val session: SessionId,
    val localRun: Run,
    val mixedHistory: List<SyntheticGatewayMessage>,
    val behavior: SyntheticGatewayBehavior,
    val runGateway: FixtureRunGateway,
)
