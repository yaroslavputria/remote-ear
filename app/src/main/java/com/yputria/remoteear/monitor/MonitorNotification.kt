package com.yputria.remoteear.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.yputria.remoteear.MainActivity
import com.yputria.remoteear.R
import com.yputria.remoteear.ui.MonitorCopy

/**
 * The monitoring notification.
 *
 * This is not decoration and not merely a platform obligation. Once the phone is left in another
 * room with the screen off, **this is the only status surface that exists** - so it carries the same
 * live state as the UI, from the same string resources, and it must never read "Listening" while the
 * streams are closed.
 *
 * The design's rule, which the wording follows: **the title carries the reason.** A collapsed
 * notification often shows the title alone, and "Paused" on its own is exactly the half-message this
 * product cannot afford.
 *
 * It is also a privacy feature: an app holding the microphone for hours should be conspicuous.
 * See docs/privacy.md.
 */
object MonitorNotification {

    const val CHANNEL_ID = "monitoring"
    const val ID = 1

    /** Low importance: visible, but never makes a sound or vibrates in a room with a sleeping child. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun build(context: Context, state: MonitorState): Notification {
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Stopped is the only state that is dismissable and the only one whose primary action opens
        // the app: it is the only state that needs the user to do something, and pinning a dead
        // notification in the shade afterwards would just be noise.
        val stopped = state is MonitorState.Error

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(MonitorCopy.notificationTitle(state)))
            .setContentText(context.getString(MonitorCopy.notificationText(state)))
            .setSmallIcon(R.drawable.ic_stat_remote_ear)
            .setContentIntent(openIntent)
            .setOngoing(!stopped && state !is MonitorState.Idle)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (stopped) {
                    addAction(0, context.getString(R.string.notif_action_open), openIntent)
                } else {
                    // Available in every other state, so ending a session never means walking back
                    // for the phone.
                    addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
                }
            }
            .build()
    }
}
