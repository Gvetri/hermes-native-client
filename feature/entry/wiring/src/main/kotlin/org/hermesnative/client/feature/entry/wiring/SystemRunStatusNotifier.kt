package org.hermesnative.client.feature.entry.wiring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import org.hermesnative.client.feature.entry.domain.Run
import org.hermesnative.client.feature.entry.domain.RunPresentationState
import org.hermesnative.client.feature.entry.domain.RunStatusNotifier
import org.hermesnative.client.feature.entry.domain.runStatusNotificationText

/**
 * Best-effort system notification for a terminal observed Run.
 *
 * All notification content is derived from [RunPresentationState] only; Run and
 * Session identifiers, raw status text, prompts, responses, credentials,
 * headers, tool data, and endpoint data are never included.
 */
class SystemRunStatusNotifier(
    context: Context,
) : RunStatusNotifier {
    private val applicationContext = context.applicationContext
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun postTerminal(
        run: Run,
        state: RunPresentationState,
    ) {
        val text = state.runStatusNotificationText() ?: return

        @Suppress("DEPRECATION")
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(applicationContext, CHANNEL_ID)
            } else {
                Notification.Builder(applicationContext)
            }
        val contentIntent = applicationContext.launchIntent()
        val notification =
            builder
                .setSmallIcon(R.drawable.ic_run_status_notification)
                .setContentTitle(TITLE)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .apply {
                    if (contentIntent != null) {
                        setContentIntent(
                            PendingIntent.getActivity(
                                applicationContext,
                                0,
                                contentIntent,
                                PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    }
                }.build()
        notificationManager.notify(run.id.value.hashCode(), notification)
    }

    private fun Context.launchIntent() = packageManager.getLaunchIntentForPackage(packageName)

    private companion object {
        const val CHANNEL_ID = "run_status"
        const val CHANNEL_NAME = "Run status"
        const val TITLE = "Hermes Native Client"
    }
}
