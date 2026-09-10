package com.yputria.remoteear.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The monitoring notification.
 *
 * This is not decoration and not merely a platform obligation. Once the phone is left in another
 * room with the screen off, **this is the only status surface that exists** - so it carries the same
 * live state as the UI, and it must never read "Monitoring" while the streams are closed.
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
            "Monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows that RemoteEar is listening, and lets you stop it."
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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title(state))
            .setContentText(detail(state))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(state !is MonitorState.Idle)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Available in every state, so ending a session never means walking back for the phone.
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    /**
     * Wording matters more than it looks here - it is the safety message. Plain, calm, and specific
     * about what to do. See docs/design-brief.md; Phase 4 replaces these with designed strings.
     */
    fun title(state: MonitorState): String = when (state) {
        MonitorState.Idle -> "Not monitoring"
        MonitorState.Starting -> "Starting…"
        is MonitorState.Monitoring -> "Monitoring"
        is MonitorState.Paused -> "Paused"
        is MonitorState.Error -> "Stopped — something went wrong"
    }

    fun detail(state: MonitorState): String = when (state) {
        MonitorState.Idle -> "Tap to open RemoteEar."
        MonitorState.Starting -> "Checking the audio route."
        is MonitorState.Monitoring -> "This room is playing to your headphones."
        is MonitorState.Paused -> when (state.reason) {
            PauseReason.BluetoothGone ->
                "Headphones disconnected. Reconnect them to continue."
            PauseReason.AudioFocusLost ->
                "Another app is using audio. Monitoring resumes when it finishes."
            PauseReason.Call ->
                "A call is in progress. Monitoring resumes when it ends."
            PauseReason.MicPreempted ->
                "Another app is using the microphone. Monitoring resumes when it stops."
        }
        is MonitorState.Error -> state.message
    }
}
