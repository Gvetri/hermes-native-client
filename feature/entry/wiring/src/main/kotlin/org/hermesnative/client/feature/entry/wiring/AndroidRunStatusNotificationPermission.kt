package org.hermesnative.client.feature.entry.wiring

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.hermesnative.client.feature.entry.domain.RunStatusNotificationPermission

/** Platform notification permission status for Run status notifications. */
class AndroidRunStatusNotificationPermission(
    private val context: Context,
) : RunStatusNotificationPermission {
    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    override fun canPost(): Boolean = notificationManager.areNotificationsEnabled()

    override fun requiresRuntimePermissionRequest(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}
