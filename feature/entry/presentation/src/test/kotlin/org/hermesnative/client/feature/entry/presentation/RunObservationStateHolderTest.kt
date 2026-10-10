package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.SessionHistory
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunObservationStateHolderTest {
    @Test
    fun interrupted_observation_preserves_partial_text_and_exposes_uncertain_state() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                observation =
                    ObservationThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val holder = observationHolder(gateway)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            val opened = requireNotNull(requireNotNull(holder.uiState.value.sessionList).openedSession)
            assertEquals(RunPresentationState.UNCERTAIN, opened.latestRunState)
            assertEquals("Partial", opened.activeResponse?.content)
            assertTrue(requireNotNull(opened.activeResponse).streamInterrupted)
            assertFalse(requireNotNull(opened.activeResponse).isStreaming)
            assertTrue(opened.activeRuns.any { it.id == run.id })
        } finally {
            holder.close()
        }
    }

    @Test
    fun returning_to_sessions_closes_only_the_screen_observer_and_does_not_cancel_the_remote_run() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-1"), session.id, "starting")
        val observation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)

            assertTrue(observation.closed.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(emptyList<SessionId>(), gateway.cancelledRunIds)
            assertTrue(holder.uiState.value.sessionList?.openedSession == null)
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun a_locally_started_nonterminal_run_is_registered_and_survives_screen_switching() {
        val session = observationSession("session-1")
        val run = Run(RunId("run-1"), session.id, "running")
        val observation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(run)
                this.observation = observation
            }
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val holder = observationHolder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(
                listOf(RunRecoveryEntry(session.id, run.id)),
                recoveryRegistry.load(),
            )

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)

            assertTrue(observation.closed.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(listOf(RunRecoveryEntry(session.id, run.id)), recoveryRegistry.load())
            assertTrue(gateway.statusRequests.isEmpty())
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun reopening_reconciles_the_locally_created_run_without_history_run_metadata() {
        val session = observationSession("session-1")
        val localRun = Run(RunId("run-local"), session.id, "running")
        val initialObservation = ObservationBlockingObservation()
        val reopenedObservation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(localRun)
                observation = initialObservation
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(initialObservation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.ReturnToSessionListClicked)
            assertTrue(initialObservation.closed.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            gateway.observation = reopenedObservation
            gateway.enqueueStatus(localRun)
            gateway.enqueueHistory(
                SessionHistory(
                    session.id,
                    listOf(GatewayHistoryMessage("result", "assistant", "Local result")),
                ),
            )
            holder.onEvent(EntryUiEvent.SessionClicked(session.id))

            awaitObservationState(holder) {
                val opened = it.sessionList?.openedSession
                gateway.statusRequests == listOf(localRun.id) &&
                    opened?.activeRuns?.any { run -> run.id == localRun.id } == true &&
                    opened.latestRun?.id == localRun.id &&
                    opened.messages.map { message -> message.runId } == listOf(null) &&
                    opened.activeResponse?.runId == localRun.id &&
                    !opened.isReconciliationInProgress
            }
            assertTrue(reopenedObservation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        } finally {
            initialObservation.release.countDown()
            reopenedObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun switching_to_another_session_closes_the_previous_screen_observer() {
        val first = observationSession("session-1")
        val second = observationSession("session-2")
        val firstObservation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(first, additionalSessions = listOf(second)).apply {
                observation = firstObservation
                enqueueRun(Run(RunId("run-switch"), first.id, "running"))
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationState(holder) {
                it.sessionList?.sessions == listOf(first, second).map { session -> session.toSessionItemUiState() }
            }

            holder.onEvent(EntryUiEvent.SessionClicked(first.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == first.id }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(firstObservation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(second.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == second.id }

            assertTrue(
                "the previous Session's screen observer closes when another Session replaces it",
                firstObservation.closed.await(1_000, TimeUnit.MILLISECONDS),
            )
        } finally {
            firstObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun searching_the_session_list_closes_the_open_conversations_observer() {
        val session = observationSession("session-search")
        val observer = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(session).apply {
                observation = observer
                enqueueRun(Run(RunId("run-search"), session.id, "running"))
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observer.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionSearchQueryChanged("filter"))
            awaitObservationState(holder) { it.sessionList?.openedSession == null }

            assertTrue(
                "the open conversation's screen observer closes when a search replaces it",
                observer.closed.await(1_000, TimeUnit.MILLISECONDS),
            )
        } finally {
            observer.release.countDown()
            holder.close()
        }
    }

    @Test
    fun a_pending_second_open_does_not_restart_the_replaced_sessions_observer() {
        val first = observationSession("session-1")
        val second = observationSession("session-2")
        val firstObservation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(first, additionalSessions = listOf(second)).apply {
                observation = firstObservation
                enqueueRun(Run(RunId("run-pending"), first.id, "running"))
                blockOpenFor = second.id
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationState(holder) {
                it.sessionList?.sessions == listOf(first, second).map { session -> session.toSessionItemUiState() }
            }

            holder.onEvent(EntryUiEvent.SessionClicked(first.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == first.id }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(firstObservation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(second.id))
            assertTrue(gateway.openStarted.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            Thread.sleep(300)
            gateway.openRelease.countDown()
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == second.id }

            assertEquals(
                "the replaced Session's observer must not restart while its replacement is pending",
                listOf(RunId("run-pending")),
                gateway.observedRunIds.toList(),
            )
        } finally {
            gateway.openRelease.countDown()
            firstObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun a_failed_switch_keeps_the_still_visible_sessions_observer() {
        val first = observationSession("session-1")
        val second = observationSession("session-2")
        val firstObservation = ObservationBlockingObservation()
        val gateway =
            ObservationScriptedGateway(first, additionalSessions = listOf(second)).apply {
                observation = firstObservation
                enqueueRun(Run(RunId("run-failed-switch"), first.id, "running"))
                failOpenFor = second.id
            }
        val holder = observationHolder(gateway, Dispatchers.Default)

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
            awaitObservationState(holder) {
                it.sessionList?.sessions == listOf(first, second).map { session -> session.toSessionItemUiState() }
            }

            holder.onEvent(EntryUiEvent.SessionClicked(first.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == first.id }

            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(firstObservation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            holder.onEvent(EntryUiEvent.SessionClicked(second.id))
            awaitObservationState(holder) {
                it.sessionList?.openingSessionId == null && it.sessionList?.isStale == true
            }

            assertFalse(
                "the still-visible Session keeps its observer when the switch fails",
                firstObservation.closed.await(400, TimeUnit.MILLISECONDS),
            )

            gateway.failOpenFor = null
            holder.onEvent(EntryUiEvent.RefreshSessionListClicked)
            awaitObservationState(holder) { it.sessionList?.isStale == false && it.sessionList?.isUnavailable == false }
            holder.onEvent(EntryUiEvent.SessionClicked(second.id))
            awaitObservationState(holder) { it.sessionList?.openedSession?.session?.id == second.id }

            assertTrue(
                "the replaced Session's observer closes once the switch finally succeeds",
                firstObservation.closed.await(1_000, TimeUnit.MILLISECONDS),
            )
        } finally {
            firstObservation.release.countDown()
            holder.close()
        }
    }

    @Test
    fun terminal_reconciliation_of_another_run_keeps_the_current_run_observer_open() {
        val session = observationSession("session-multiple-runs")
        val observedRun = Run(RunId("run-observed"), session.id, "running")
        val reconciledRun = Run(RunId("run-reconciled"), session.id, "succeeded")
        val observation = ObservationBlockingObservation()
        val recoveryRegistry = InMemoryRunRecoveryRegistry()
        val gateway =
            ObservationScriptedGateway(session).apply {
                enqueueRun(observedRun)
                this.observation = observation
                statusByRun[reconciledRun.id] = reconciledRun
            }
        val holder = observationHolder(gateway, Dispatchers.Default, recoveryRegistry)

        try {
            openObservedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)
            assertTrue(observation.started.await(OBSERVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            recoveryRegistry.save(RunRecoveryEntry(session.id, reconciledRun.id))
            holder.onEvent(EntryUiEvent.RefreshSessionsClicked)

            awaitObservationCondition { gateway.statusRequests.contains(reconciledRun.id) }
            assertEquals(listOf(observedRun.id), gateway.observedRunIds)
            assertFalse(observation.closed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            observation.release.countDown()
            holder.close()
        }
    }
}
