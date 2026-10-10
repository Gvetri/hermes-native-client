package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RunObservationRecoveryTest {
    @Test
    fun one_foreground_observer_appends_deltas_and_reaches_succeeded_without_duplicate_content() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("assistant-1", "assistant", "Hello world", run.id, "succeeded")),
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    ObservationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.RUNNING, run.id, "running", eventId = "running"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Hello", "delta-1"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Hello", "delta-1"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", " world", "delta-2"),
                            RunEvent(RunEventType.COMPLETING, run.id, "completing", eventId = "completing"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                        ),
                    )
            }
        val holder = observationHolder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(listOf(run.id), gateway.observedRunIds)
            assertEquals(RunPresentationState.SUCCEEDED, opened.latestRunState)
            assertEquals(listOf("Hello world"), opened.messages.map { it.content })
            assertTrue(opened.activeResponse == null)
            assertTrue(recoveryRegistry.load().isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun disconnect_during_create_persists_the_remote_run_in_its_original_endpoint_scope() {
        val session = observationSession("disconnect-during-create")
        val run = Run(RunId("run-disconnected"), session.id, "running")
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                blockCreate = true
            }
        val persisted = CopyOnWriteArrayList<Pair<String, RunRecoveryEntry>>()
        val holder =
            observationHolder(
                gateway = gateway,
                dispatcher = Dispatchers.Default,
                persistRunRecoveryEntry = { endpoint, entry -> persisted += endpoint to entry },
            )

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.createStarted.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            gateway.createRelease.countDown()

            awaitObservationCondition { persisted.isNotEmpty() }
            assertEquals(
                listOf("https://gateway.example/profile" to RunRecoveryEntry(session.id, run.id)),
                persisted.toList(),
            )
            assertTrue(gateway.cancelledRunIds.isEmpty())
        } finally {
            gateway.createRelease.countDown()
            holder.close()
        }
    }

    @Test
    fun late_create_result_reconciles_after_reconnecting_before_recovery_metadata_is_written() {
        val session = observationSession("disconnect-reconnect-race")
        val run = Run(RunId("run-reconnect-race"), session.id, "running")
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                statusByRun[run.id] = run
                blockCreate = true
            }
        val persisted = CopyOnWriteArrayList<Pair<String, RunRecoveryEntry>>()
        val holder =
            observationHolder(
                gateway = gateway,
                dispatcher = Dispatchers.Default,
                persistRunRecoveryEntry = { endpoint, entry -> persisted += endpoint to entry },
            )

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(gateway.createStarted.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationState(holder) { it.sessionList?.sessions == listOf(session.toSessionItemUiState()) }

            gateway.createRelease.countDown()

            awaitObservationCondition {
                persisted.isNotEmpty() &&
                    gateway.statusRequests.contains(run.id)
            }
        } finally {
            gateway.createRelease.countDown()
            holder.close()
        }
    }

    @Test
    fun recovery_persistence_failure_keeps_the_created_run_and_marks_its_outcome_uncertain() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-1"), session.id, "running")
        val observation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val holder = observationHolder(gateway, Dispatchers.Default, ObservationFailingRunRecoveryRegistry())

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitObservationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRun?.id == run.id &&
                    opened.latestRunState == RunPresentationState.UNCERTAIN &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN &&
                    opened.hasUnresolvedSubmission &&
                    opened.composerText == "Run this"
            }
            assertTrue(observation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun recovery_persistence_failure_is_retried_after_connection_recreation() {
        val session = observationSession("recovery-retry")
        val run = Run(RunId("run-recovery-retry"), session.id, "running")
        val observation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val recoveryRegistry = ObservationFlakyRunRecoveryRegistry()
        val holder = observationHolder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Retry this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitObservationState(holder) {
                it.sessionList?.openedSession?.hasUnresolvedSubmission == true
            }

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)
            recoveryRegistry.failSave = false
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            awaitObservationCondition {
                recoveryRegistry.load() == listOf(RunRecoveryEntry(session.id, run.id))
            }
            assertEquals(1, gateway.createRunCount.get())
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun recovery_load_failure_blocks_submission_until_authoritative_recovery_is_available() {
        val gateway = ObservationScriptedGateway(observationSession("recovery-load-failure"))
        val holder = observationHolder(gateway, recoveryRegistry = ObservationFailingLoadRunRecoveryRegistry())

        try {
            openObservedSession(holder, gateway, gateway.session.id)
            awaitObservationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.hasUnresolvedSubmission == true &&
                    opened.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN
            }
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Do not duplicate"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertEquals(0, gateway.createRunCount.get())
        } finally {
            holder.close()
        }
    }

    @Test
    fun restart_reconciles_only_known_local_nonterminal_runs() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-local"), session.id, "running")
        val recoveryEntry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(recoveryEntry))
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueStatus(run)
            }
        val holder = observationHolder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            awaitObservationState(holder) {
                it.sessionList?.sessions == listOf(session.toSessionItemUiState()) &&
                    gateway.statusRequests == listOf(run.id)
            }
            assertEquals(listOf(recoveryEntry), recoveryRegistry.load())
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun recovery_removal_failure_keeps_metadata_without_failing_the_recovery_job() {
        val session = observationSession("session-remove-failure")
        val run = Run(RunId("run-remove-failure"), session.id, "succeeded")
        val entry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = ObservationFailingRemoveRunRecoveryRegistry(entry)
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueStatus(run)
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Done", run.id, "succeeded")),
                    ),
                )
            }
        val holder = observationHolder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationCondition { gateway.statusRequests.contains(run.id) }
            assertEquals(listOf(entry), recoveryRegistry.load())
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_observation_forgets_a_recovery_write_queued_after_save_failure() {
        val session = observationSession("session-queued-removal")
        val run = Run(RunId("run-queued-removal"), session.id, "running")
        val observation =
            ObservationDelayedTerminalObservation(
                RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
            )
        val recoveryRegistry = ObservationFlakyRunRecoveryRegistry()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Done", run.id, "succeeded")),
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                this.observation = observation
            }
        val holder = observationHolder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(1, recoveryRegistry.saveAttempts.get())

            recoveryRegistry.failSave = false
            observation.release.countDown()
            awaitObservationState(holder) {
                val opened = it.sessionList?.openedSession
                opened?.latestRunState == RunPresentationState.SUCCEEDED &&
                    opened.hasUnresolvedSubmission == false
            }
            assertTrue(recoveryRegistry.load().isEmpty())

            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)
            awaitObservationState(holder) { it.sessionList?.isRefreshing == false }
            assertEquals(1, recoveryRegistry.saveAttempts.get())
            assertTrue(recoveryRegistry.load().isEmpty())
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun recovery_continues_after_switching_sessions_while_authoritative_queries_are_blocked() {
        val firstSession = observationSession("session-recovery-1")
        val secondSession = observationSession("session-recovery-2")
        val firstRun = Run(RunId("run-recovery-1"), firstSession.id, "running")
        val secondRun = Run(RunId("run-recovery-2"), secondSession.id, "running")
        val recoveryRegistry =
            InMemoryRunRecoveryRegistry(
                listOf(
                    RunRecoveryEntry(firstSession.id, firstRun.id),
                    RunRecoveryEntry(secondSession.id, secondRun.id),
                ),
            )
        val gateway =
            ObservationScriptedGateway(firstSession, listOf(secondSession)).apply {
                blockStatus = true
                statusByRun[firstRun.id] = firstRun.copy(status = "succeeded")
                statusByRun[secondRun.id] = secondRun.copy(status = "succeeded")
                historyBySession[firstSession.id] =
                    SessionHistory(
                        firstSession.id,
                        listOf(GatewayHistoryMessage("first-result", "assistant", "Done", firstRun.id, "succeeded")),
                    )
                historyBySession[secondSession.id] =
                    SessionHistory(
                        secondSession.id,
                        listOf(GatewayHistoryMessage("second-result", "assistant", "Done", secondRun.id, "succeeded")),
                    )
            }
        val holder = observationHolder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("test-only-credential"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationState(holder) { it.sessionList?.sessions?.size == 2 }
            assertTrue(gateway.statusStarted.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(firstSession.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == firstSession.id }
            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            holder.onEvent(EntryUiEvent.SessionClicked(secondSession.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == secondSession.id }

            gateway.statusRelease.countDown()
            awaitObservationCondition {
                recoveryRegistry.load().isEmpty() &&
                    gateway.statusRequests.containsAll(listOf(firstRun.id, secondRun.id))
            }
        } finally {
            gateway.statusRelease.countDown()
            holder.close()
        }
    }

    @Test
    fun restart_removes_a_recovery_entry_only_after_terminal_history_confirms_the_run() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-local"), session.id, "succeeded")
        val recoveryEntry = RunRecoveryEntry(session.id, run.id)
        val recoveryRegistry = InMemoryRunRecoveryRegistry(listOf(recoveryEntry))
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueStatus(run)
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(GatewayHistoryMessage("result", "assistant", "Done", run.id, "succeeded")),
                    ),
                )
            }
        val holder = observationHolder(gateway, recoveryRegistry = recoveryRegistry)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            awaitObservationState(holder) {
                it.sessionList?.sessions == listOf(session.toSessionItemUiState()) &&
                    gateway.statusRequests == listOf(run.id) &&
                    recoveryRegistry.load().isEmpty()
            }
            assertTrue(gateway.observedRunIds.isEmpty())
        } finally {
            holder.close()
        }
    }
}
