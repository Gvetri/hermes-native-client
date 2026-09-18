package org.hermesnative.client.feature.entry.presentation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hermesnative.client.feature.entry.application.EntryState
import org.hermesnative.client.feature.entry.application.RemoveGatewayConnection
import org.hermesnative.client.feature.entry.application.VerifyGatewayConnection
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.data.InMemoryRunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.GatewayCapabilities
import org.hermesnative.client.feature.entry.domain.GatewayConnectionRepository
import org.hermesnative.client.feature.entry.domain.PublicBetaGatewayCapabilityManifest
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunEvent
import org.hermesnative.client.feature.entry.domain.RunEventObservation
import org.hermesnative.client.feature.entry.domain.RunEventType
import org.hermesnative.client.feature.entry.domain.RunGatewayPort
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunRecoveryEntry
import org.hermesnative.client.feature.entry.domain.RunRecoveryRegistry
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationPermission
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationSettingsStore
import org.hermesnative.client.feature.entry.domain.RunStatusNotifier
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class RunStatusNotificationStateHolderTest {
    @Test
    fun default_install_state_is_disabled_and_first_launch_never_requests_permission() {
        val session = session("session-default")
        val gateway = ScriptedGateway(session)
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = true)
        val notifier = FakeRunStatusNotifier()
        val permissionRequests = AtomicInteger(0)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                notifier = notifier,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)

            assertFalse(holder.uiState.value.runStatusNotifications.enabled)
            assertNull(holder.uiState.value.runStatusNotifications.explanation)
            assertEquals(0, permissionRequests.get())
            assertTrue(notifier.posted.isEmpty())
            assertFalse(store.enabled)
        } finally {
            holder.close()
        }
    }

    @Test
    fun run_creation_does_not_request_notification_permission_while_disabled() {
        val session = session("session-create")
        val run = Run(RunId("run-create"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    ScriptedObservation(
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
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                notifier = notifier,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(0, permissionRequests.get())
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun enabling_with_granted_permission_persists_and_shows_enabled() {
        val session = session("session-enable")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false)
        val permissionRequests = AtomicInteger(0)
        val gateway = ScriptedGateway(session)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked)

            assertTrue(holder.uiState.value.runStatusNotifications.enabled)
            assertTrue(store.enabled)
            assertNull(holder.uiState.value.runStatusNotifications.explanation)
            assertEquals(0, permissionRequests.get())
        } finally {
            holder.close()
        }
    }

    @Test
    fun disabling_persists_and_hides_any_explanation() {
        val session = session("session-disable")
        val store = FakeNotificationSettingsStore(enabled = true)
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false)
        val permissionRequests = AtomicInteger(0)
        val gateway = ScriptedGateway(session)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            assertTrue(holder.uiState.value.runStatusNotifications.enabled)
            holder.onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked)

            assertFalse(holder.uiState.value.runStatusNotifications.enabled)
            assertFalse(store.enabled)
            assertEquals(0, permissionRequests.get())
        } finally {
            holder.close()
        }
    }

    @Test
    fun enabling_when_runtime_permission_is_required_requests_permission_before_enabling() {
        val session = session("session-request")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = true)
        val permissionRequests = AtomicInteger(0)
        val gateway = ScriptedGateway(session)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked)

            assertEquals(1, permissionRequests.get())
            assertFalse(holder.uiState.value.runStatusNotifications.enabled)
            assertFalse(store.enabled)

            holder.onEvent(EntryUiEvent.RunStatusNotificationPermissionResult(granted = true))
            assertTrue(holder.uiState.value.runStatusNotifications.enabled)
            assertTrue(store.enabled)
            assertNull(holder.uiState.value.runStatusNotifications.explanation)
        } finally {
            holder.close()
        }
    }

    @Test
    fun denied_permission_keeps_setting_disabled_and_explains_recoverably() {
        val session = session("session-denied")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = true)
        val permissionRequests = AtomicInteger(0)
        val gateway = ScriptedGateway(session)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = permission,
                permissionRequests = permissionRequests,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked)
            store.enabled = true // stale persisted preference must not survive denial
            holder.onEvent(EntryUiEvent.RunStatusNotificationPermissionResult(granted = false))

            val notifications = holder.uiState.value.runStatusNotifications
            assertFalse(notifications.enabled)
            assertEquals(RunStatusNotificationExplanation.PERMISSION_DENIED, notifications.explanation)
            assertFalse(store.enabled)
            assertEquals(1, permissionRequests.get())
        } finally {
            holder.close()
        }
    }

    @Test
    fun persisted_enabled_without_platform_permission_restores_disabled_with_explanation() {
        val session = session("session-restore")
        val store = FakeNotificationSettingsStore(enabled = true)
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = false)
        val holder =
            holder(
                gateway = ScriptedGateway(session),
                store = store,
                permission = permission,
            )

        try {
            val notifications = holder.uiState.value.runStatusNotifications
            assertFalse(notifications.enabled)
            assertEquals(RunStatusNotificationExplanation.PERMISSION_DENIED, notifications.explanation)
            assertFalse(store.enabled)
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_success_posts_exactly_one_notification() {
        val session = session("session-notify-success")
        val run = Run(RunId("run-success"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "succeeded"))
                observation =
                    ScriptedObservation(
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
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(listOf(run.id to RunPresentationState.SUCCEEDED), notifier.posted.toList())
            assertEquals(
                listOf("Stable result"),
                holder.uiState.value.sessionList?.openedSession?.messages?.map { it.content },
            )
        } finally {
            holder.close()
        }
    }

    @Test
    fun terminal_failure_posts_failed_notification() {
        val session = session("session-notify-failure")
        val run = Run(RunId("run-failure"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
                observation =
                    ScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.FAILED, run.id, "failed", eventId = "failed"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.FAILED
            }
            assertEquals(listOf(run.id to RunPresentationState.FAILED), notifier.posted.toList())
        } finally {
            holder.close()
        }
    }

    @Test
    fun cancelled_and_uncertain_outcomes_never_notify() {
        val session = session("session-cancelled")
        val run = Run(RunId("run-cancelled"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "cancelled"))
                observation =
                    ScriptedObservation(
                        listOf(
                            RunEvent(RunEventType.COMPLETED, run.id, "cancelled", eventId = "completed"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.CANCELLED
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_notifies_nothing_and_preserves_uncertain_behavior() {
        val session = session("session-interrupted")
        val run = Run(RunId("run-interrupted"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                observation =
                    ThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.UNCERTAIN
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun interrupted_observation_confirmed_terminal_by_authoritative_refetch_notifies_exactly_once() {
        val session = session("session-refetch-confirmed")
        val run = Run(RunId("run-refetch-confirmed"), session.id, "starting")
        val gateway =
            ScriptedGateway(session).apply {
                enqueueRun(run)
                enqueueHistory(SessionHistory(session.id, emptyList(), null))
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
                observation =
                    ThrowingObservation(
                        listOf(
                            RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                            RunEvent(RunEventType.MESSAGE_DELTA, run.id, "running", "Partial", "delta-1"),
                        ),
                    )
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            holder.onEvent(EntryUiEvent.SendMessageClicked)

            awaitState(holder) {
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
        val session = session("session-recovery-restart")
        val run = Run(RunId("run-recovery-restart"), session.id, "running")
        val recoveryRegistry = InMemoryRunRecoveryRegistry().apply { save(RunRecoveryEntry(session.id, run.id)) }
        val gateway =
            ScriptedGateway(session).apply {
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
                        null,
                    ),
                )
                enqueueStatus(run.copy(status = "failed"))
            }
        val notifier = FakeRunStatusNotifier()
        val holder =
            holder(
                gateway = gateway,
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
                recoveryRegistry = recoveryRegistry,
            )

        try {
            holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
            holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
            holder.onEvent(EntryUiEvent.BearerCredentialChanged("memory-only-token"))
            holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)

            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    while (recoveryRegistry.load().isNotEmpty()) delay(10)
                }
            }
            assertTrue(notifier.posted.isEmpty())
        } finally {
            holder.close()
        }
    }

    @Test
    fun removing_the_gateway_connection_preserves_the_notification_setting() {
        val session = session("session-remove-preserves-setting")
        val gateway = ScriptedGateway(session)
        val store = FakeNotificationSettingsStore(enabled = true)
        val holder =
            holder(
                gateway = gateway,
                store = store,
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
            )

        try {
            connectAndOpenSession(holder, gateway, session.id)
            assertTrue(holder.uiState.value.runStatusNotifications.enabled)

            holder.onEvent(EntryUiEvent.RemoveGatewayConnectionClicked)

            assertTrue(holder.uiState.value.runStatusNotifications.enabled)
            assertTrue(store.enabled)
            assertNull(holder.uiState.value.sessionList)
        } finally {
            holder.close()
        }
    }

    @Test
    fun disabled_or_denied_runs_complete_normally_without_notifications() {
        val session = session("session-disabled-run")
        val notifier = FakeRunStatusNotifier()
        val disabledHolder =
            holder(
                gateway = successfulGateway(session, "run-disabled"),
                store = FakeNotificationSettingsStore(enabled = false),
                permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )
        val deniedHolder =
            holder(
                gateway = successfulGateway(session, "run-denied"),
                store = FakeNotificationSettingsStore(enabled = true),
                permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = false),
                notifier = notifier,
            )

        try {
            val disabledGateway = successfulGateway(session, "run-disabled")
            connectAndOpenSession(disabledHolder, disabledGateway, session.id)
            disabledHolder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            disabledHolder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(disabledHolder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(
                listOf("Hello world"),
                disabledHolder.uiState.value.sessionList?.openedSession?.messages?.map { it.content },
            )

            val deniedGateway = successfulGateway(session, "run-denied")
            connectAndOpenSession(deniedHolder, deniedGateway, session.id)
            deniedHolder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            deniedHolder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitState(deniedHolder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(
                listOf("Hello world"),
                deniedHolder.uiState.value.sessionList?.openedSession?.messages?.map { it.content },
            )

            assertTrue(notifier.posted.isEmpty())
        } finally {
            disabledHolder.close()
            deniedHolder.close()
        }
    }

    private fun successfulGateway(
        session: Session,
        runId: String,
    ): ScriptedGateway {
        val run = Run(RunId(runId), session.id, "starting")
        return ScriptedGateway(session).apply {
            enqueueRun(run)
            enqueueHistory(SessionHistory(session.id, emptyList(), null))
            enqueueHistory(
                SessionHistory(
                    session.id,
                    listOf(
                        org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage(
                            id = "assistant-1",
                            role = "assistant",
                            content = "Hello world",
                            runId = run.id,
                            runStatus = "succeeded",
                        ),
                    ),
                    null,
                ),
            )
            enqueueStatus(run.copy(status = "succeeded"))
            observation =
                ScriptedObservation(
                    listOf(
                        RunEvent(RunEventType.STARTED, run.id, "starting", eventId = "started"),
                        RunEvent(RunEventType.SUCCEEDED, run.id, "succeeded", eventId = "succeeded"),
                    ),
                )
        }
    }

    private fun holder(
        gateway: ScriptedGateway,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        store: FakeNotificationSettingsStore = FakeNotificationSettingsStore(),
        permission: FakeNotificationPermission =
            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
        notifier: FakeRunStatusNotifier = FakeRunStatusNotifier(),
        permissionRequests: AtomicInteger = AtomicInteger(0),
        recoveryRegistry: RunRecoveryRegistry? = null,
    ): EntryStateHolder {
        val repository: GatewayConnectionRepository =
            DefaultGatewayConnectionRepository(InMemoryGatewayConnectionDataSource())
        return EntryStateHolder(
            initialState = EntryState(isGatewayConnectionConfigured = false),
            verifyGatewayConnection =
                VerifyGatewayConnection(repository) { _, _ ->
                    GatewayCapabilities(PublicBetaGatewayCapabilityManifest.current.requiredIdentifiers)
                },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            sessionGatewayFactory = { _, _ -> gateway },
            runGatewayFactory = { _, _ -> gateway },
            runRecoveryRegistry = recoveryRegistry,
            removeGatewayConnectionUseCase = RemoveGatewayConnection(repository),
            runStatusNotificationSettingsStore = store,
            runStatusNotificationPermission = permission,
            runStatusNotifier = notifier,
        ).also { holder -> holder.requestRunStatusNotificationPermission = { permissionRequests.incrementAndGet() } }
    }

    private fun connectAndOpenSession(
        holder: EntryStateHolder,
        gateway: ScriptedGateway,
        sessionId: SessionId,
    ) {
        holder.onEvent(EntryUiEvent.AddGatewayConnectionClicked)
        holder.onEvent(EntryUiEvent.EndpointChanged("https://gateway.example/profile"))
        holder.onEvent(EntryUiEvent.BearerCredentialChanged("[REDACTED]"))
        holder.onEvent(EntryUiEvent.VerifyGatewayConnectionClicked)
        awaitState(holder) { it.sessionList?.sessions == listOf(gateway.session.toSessionItemUiState()) }
        holder.onEvent(EntryUiEvent.SessionClicked(sessionId))
        awaitState(holder) { it.sessionList?.openedSession?.session?.id == sessionId }
    }

    private fun awaitState(
        holder: EntryStateHolder,
        predicate: (EntryUiState) -> Boolean,
    ) {
        runBlocking {
            try {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    holder.uiState.first(predicate)
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val state = holder.uiState.value
                error(
                    "Timed out waiting for state. Current: " +
                        "opened=${state.sessionList?.openedSession != null}, " +
                        "latestRunState=${state.sessionList?.openedSession?.latestRunState}, " +
                        "sendError=${state.sessionList?.openedSession?.sendErrorCategory}, " +
                        "error=${state.sessionList?.openedSession?.errorCategory}, " +
                        "composer=${state.sessionList?.openedSession?.composerText}, " +
                        "runs=${state.sessionList?.openedSession?.activeRuns?.map { it.id }}, " +
                        "sessions=${state.sessionList?.sessions?.map { it.id }}",
                )
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

    private class FakeNotificationSettingsStore(
        var enabled: Boolean = false,
    ) : RunStatusNotificationSettingsStore {
        override fun loadEnabled(): Boolean = enabled

        override fun saveEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private class FakeNotificationPermission(
        var canPost: Boolean,
        var requiresRuntimePermissionRequest: Boolean,
    ) : RunStatusNotificationPermission {
        override fun canPost(): Boolean = canPost

        override fun requiresRuntimePermissionRequest(): Boolean = requiresRuntimePermissionRequest
    }

    private class FakeRunStatusNotifier : RunStatusNotifier {
        val posted = CopyOnWriteArrayList<Pair<RunId, RunPresentationState>>()

        override fun postTerminal(
            run: Run,
            state: RunPresentationState,
        ) {
            posted += run.id to state
        }
    }

    private class ScriptedGateway(
        val session: Session,
    ) : SessionGatewayPort, RunGatewayPort {
        private val runResults = java.util.ArrayDeque<Run>()
        private val historyResults = java.util.ArrayDeque<SessionHistory>()
        private val statusResults = java.util.ArrayDeque<Run>()
        var observation: RunEventObservation = ScriptedObservation(emptyList())

        fun enqueueRun(run: Run) {
            runResults += run
        }

        fun enqueueHistory(history: SessionHistory) {
            historyResults += history
        }

        fun enqueueStatus(run: Run) {
            statusResults += run
        }

        override fun listSessions(request: SessionListRequest): SessionPage = SessionPage(listOf(session), null)

        override fun createSession(title: String?): Session = error("not used")

        override fun openSession(sessionId: SessionId): Session = session

        override fun loadSessionHistory(sessionId: SessionId): SessionHistory =
            if (historyResults.isEmpty()) {
                SessionHistory(sessionId, emptyList(), null)
            } else {
                historyResults.removeFirst()
            }

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
        ): Run = runResults.removeFirst()

        override fun getRunStatus(runId: RunId): Run =
            if (statusResults.isEmpty()) {
                error("not used")
            } else {
                statusResults.removeFirst()
            }

        override fun observeRun(runId: RunId): RunEventObservation = observation
    }

    private open class ScriptedObservation(
        private val events: List<RunEvent>,
    ) : RunEventObservation {
        override fun iterator(): Iterator<RunEvent> = events.iterator()

        override fun close() = Unit
    }

    private class ThrowingObservation(
        events: List<RunEvent>,
    ) : ScriptedObservation(events) {
        override fun iterator(): Iterator<RunEvent> {
            val delegate = super.iterator()
            return object : Iterator<RunEvent> {
                override fun hasNext(): Boolean =
                    if (delegate.hasNext()) {
                        true
                    } else {
                        throw IllegalStateException("stream interrupted")
                    }

                override fun next(): RunEvent = delegate.next()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
