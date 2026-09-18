package org.hermesnative.client.feature.entry.wiring

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunId
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunStatusNotificationWiringTest {
    @Test
    fun settings_store_defaults_to_disabled_and_round_trips() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesRunStatusNotificationSettingsStore(context)

        assertFalse(store.loadEnabled())
        store.saveEnabled(true)
        assertTrue(SharedPreferencesRunStatusNotificationSettingsStore(context).loadEnabled())
        store.saveEnabled(false)
        assertFalse(SharedPreferencesRunStatusNotificationSettingsStore(context).loadEnabled())
    }

    @Test
    fun permission_requires_runtime_request_only_when_platform_permission_is_missing() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val permission = AndroidRunStatusNotificationPermission(context)
        assertTrue(permission.requiresRuntimePermissionRequest())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AndroidRunStatusNotificationPermission(context).requiresRuntimePermissionRequest())
    }

    @Test
    fun permission_can_post_tracks_platform_notification_setting() {
        val context = RuntimeEnvironment.getApplication()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val permission = AndroidRunStatusNotificationPermission(context)

        shadowOf(notificationManager).setNotificationsEnabled(true)
        assertTrue(permission.canPost())

        shadowOf(notificationManager).setNotificationsEnabled(false)
        assertFalse(permission.canPost())
    }

    @Test
    fun notifier_posts_fixed_text_for_terminal_success_only() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = SystemRunStatusNotifier(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(notificationManager)
        val run = Run(RunId("run-secret"), SessionId("session-secret"), "succeeded")

        notifier.postTerminal(run, RunPresentationState.SUCCEEDED)

        val notification = shadow.getNotification(run.id.value.hashCode())
        assertEquals(
            "Hermes Native Client",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
        assertEquals(
            "Run succeeded",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun notifier_posts_fixed_text_for_terminal_failure_only() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = SystemRunStatusNotifier(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(notificationManager)
        val run = Run(RunId("run-failure-secret"), SessionId("session-secret"), "failed")

        notifier.postTerminal(run, RunPresentationState.FAILED)

        val notification = shadow.getNotification(run.id.value.hashCode())
        assertEquals(
            "Run failed",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun notification_content_never_contains_run_or_session_identifiers() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = SystemRunStatusNotifier(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(notificationManager)
        val run = Run(RunId("run-sensitive-identifier"), SessionId("session-sensitive-identifier"), "succeeded")

        notifier.postTerminal(run, RunPresentationState.SUCCEEDED)

        val notification = shadow.getNotification(run.id.value.hashCode())
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        assertEquals("Run succeeded", text)
        assertFalse(text.contains(run.id.value))
        assertFalse(text.contains(run.sessionId.value))
        assertFalse(title.contains(run.id.value))
        assertFalse(title.contains(run.sessionId.value))
    }

    @Test
    fun notifier_posts_nothing_for_non_notifiable_states() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = SystemRunStatusNotifier(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(notificationManager)
        val run = Run(RunId("run-running"), SessionId("session-1"), "running")

        notifier.postTerminal(run, RunPresentationState.RUNNING)
        notifier.postTerminal(run, RunPresentationState.CANCELLED)
        notifier.postTerminal(run, RunPresentationState.UNCERTAIN)
        notifier.postTerminal(run, RunPresentationState.COMPLETING)
        notifier.postTerminal(run, RunPresentationState.STARTING)

        assertTrue(shadow.allNotifications.isEmpty())
    }
}
