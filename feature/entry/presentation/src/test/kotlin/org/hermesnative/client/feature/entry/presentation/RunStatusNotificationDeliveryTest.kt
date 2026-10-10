package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
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
import java.util.concurrent.atomic.AtomicInteger

class RunStatusNotificationDeliveryTest {
    @Test
    fun run_creation_does_not_request_notification_permission_while_disabled() {
        val session = notificationSession("session-create")
        val run = Run(RunId("run-create"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-create",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "succeeded",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    NotificationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                        ),
                    )
            }
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = true)
        val notifier = FakeRunStatusNotifier()
        val permissionRequests = AtomicInteger(0)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                        notifier = notifier,
                        permissionRequests = permissionRequests,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(0, permissionRequests.get())
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_success_posts_exactly_one_notification() {
        val session = notificationSession("session-notify-success")
        val run = Run(RunId("run-success"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-success",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "succeeded",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    NotificationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.RUNNING, run.id, "running", eventId = "running"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Hello", "delta-1"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                            RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded-dup"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(listOf(run.id to RunPresentationState.SUCCEEDED), notifier.posted.toList())
            val contents = holder.uiState.value.sessionList?.openedSession?.messages?.map { it.content }
            assertEquals(listOf("Stable result"), contents)
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_failure_posts_failed_notification() {
        val session = notificationSession("session-notify-failure")
        val run = Run(RunId("run-failure"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-failure",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "failed",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
                observation =
                    NotificationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.FAILED, run.id, "failed", eventId = "failed"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.FAILED
            }
            assertEquals(listOf(run.id to RunPresentationState.FAILED), notifier.posted.toList())
        } finally {
            holder.close()
        }
    }

    @Test
    fun cancelled_and_uncertain_outcomes_never_notify() {
        val session = notificationSession("session-cancelled")
        val run = Run(RunId("run-cancelled"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-cancelled",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "cancelled",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "cancelled"))
                observation =
                    NotificationScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.COMPLETED, run.id, "cancelled", eventId = "completed"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.CANCELLED
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_notifies_nothing_and_preserves_uncertain_behavior() {
        val session = notificationSession("session-interrupted")
        val run = Run(RunId("run-interrupted"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                observation =
                    NotificationThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_confirmed_terminal_by_authoritative_refetch_notifies_exactly_once() {
        val session = notificationSession("session-refetch-confirmed")
        val run = Run(RunId("run-refetch-confirmed"), session.id, "starting")
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList()))
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-refetch",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "failed",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
                observation =
                    NotificationThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitNotificationState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.FAILED
            }
            assertEquals(listOf(run.id to RunPresentationState.FAILED), notifier.posted.toList())
            assertEquals(
                listOf("Stable result"),
                holder.uiState.value.sessionList?.openedSession?.messages?.map { it.content },
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun recovery_confirmed_terminal_after_restart_does_not_notify() {
        val session = notificationSession("session-recovery-restart")
        val run = Run(RunId("run-recovery-restart"), session.id, "running")
        val recoveryRegistry = InMemoryRunRecoveryRegistry().apply { save(RunRecoveryEntry(session.id, run.id)) }
        val gateway =
            NotificationScriptedGateway(session).apply {
                enqueueHistory(
                    SessionHistory(
                        session.id,
                        listOf(
                            org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                                id = "assistant-recovery",
                                role = "assistant",
                                content = "Stable result",
                                runId = run.id,
                                runStatus = "failed",
                            ),
                        ),
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                        recoveryRegistry = recoveryRegistry,
                    ),
            )

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            runBlocking {
                withTimeout(NOTIFICATION_TIMEOUT_MILLIS) {
                    while (recoveryRegistry.load().isNotEmpty()) delay(10)
                }
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }
}
