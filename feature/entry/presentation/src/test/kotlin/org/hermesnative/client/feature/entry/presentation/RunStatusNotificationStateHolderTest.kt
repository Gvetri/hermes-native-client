package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RunStatusNotificationStateHolderTest {
    @Test
    fun default_install_state_is_disabled_and_first_launch_never_requests_permission() {
        val session = notificationSession("session-default")
        val gateway = NotificationScriptedGateway(session)
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
    fun enabling_with_granted_permission_persists_and_shows_enabled() {
        val session = notificationSession("session-enable")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false)
        val permissionRequests = AtomicInteger(0)
        val gateway = NotificationScriptedGateway(session)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                        permissionRequests = permissionRequests,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
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
        val session = notificationSession("session-disable")
        val store = FakeNotificationSettingsStore(enabled = true)
        val permission = FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false)
        val permissionRequests = AtomicInteger(0)
        val gateway = NotificationScriptedGateway(session)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                        permissionRequests = permissionRequests,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
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
        val session = notificationSession("session-request")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = true)
        val permissionRequests = AtomicInteger(0)
        val gateway = NotificationScriptedGateway(session)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                        permissionRequests = permissionRequests,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
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
        val session = notificationSession("session-denied")
        val store = FakeNotificationSettingsStore()
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = true)
        val permissionRequests = AtomicInteger(0)
        val gateway = NotificationScriptedGateway(session)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                        permissionRequests = permissionRequests,
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
            holder.onEvent(EntryUiEvent.RunStatusNotificationsToggleClicked)
            store.enabled = true
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
        val session = notificationSession("session-restore")
        val store = FakeNotificationSettingsStore(enabled = true)
        val permission = FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = false)
        val holder =
            notificationHolder(
                gateway = NotificationScriptedGateway(session),
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission = permission,
                    ),
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
    fun removing_the_gateway_connection_preserves_the_notification_setting() {
        val session = notificationSession("session-remove-preserves-setting")
        val gateway = NotificationScriptedGateway(session)
        val store = FakeNotificationSettingsStore(enabled = true)
        val holder =
            notificationHolder(
                gateway = gateway,
                settings =
                    NotificationHolderSettings(
                        store = store,
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                    ),
            )

        try {
            connectAndOpenNotifiedSession(holder, gateway, session.id)
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
        val session = notificationSession("session-disabled-run")
        val notifier = FakeRunStatusNotifier()
        val disabledHolder =
            notificationHolder(
                gateway = notificationSuccessfulGateway(session, "run-disabled"),
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = false),
                        permission =
                            FakeNotificationPermission(canPost = true, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )
        val deniedHolder =
            notificationHolder(
                gateway = notificationSuccessfulGateway(session, "run-denied"),
                settings =
                    NotificationHolderSettings(
                        store = FakeNotificationSettingsStore(enabled = true),
                        permission =
                            FakeNotificationPermission(canPost = false, requiresRuntimePermissionRequest = false),
                        notifier = notifier,
                    ),
            )

        try {
            val disabledGateway = notificationSuccessfulGateway(session, "run-disabled")
            connectAndOpenNotifiedSession(disabledHolder, disabledGateway, session.id)
            disabledHolder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            disabledHolder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitNotificationState(disabledHolder) {
                it.sessionList?.openedSession?.latestRunState == RunPresentationState.SUCCEEDED
            }
            assertEquals(
                listOf("Hello world"),
                disabledHolder.uiState.value.sessionList?.openedSession?.messages?.map { it.content },
            )

            val deniedGateway = notificationSuccessfulGateway(session, "run-denied")
            connectAndOpenNotifiedSession(deniedHolder, deniedGateway, session.id)
            deniedHolder.onEvent(EntryUiEvent.ComposerTextChanged("Run this"))
            deniedHolder.onEvent(EntryUiEvent.SendMessageClicked)
            awaitNotificationState(deniedHolder) {
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
}
