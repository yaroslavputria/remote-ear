package com.yputria.remoteear.ui

import androidx.annotation.StringRes
import com.yputria.remoteear.R
import com.yputria.remoteear.monitor.ErrorKind
import com.yputria.remoteear.monitor.MonitorState
import com.yputria.remoteear.monitor.PauseReason

/**
 * Every mapping from state to words, for both surfaces, in one file.
 *
 * The design's rule is that **the screen and the notification must never disagree**: the phone is in
 * another room, so the notification is often the only thing the user can see, and a notification
 * saying "Listening" while the screen says "Paused" would be the worst bug this product can have.
 *
 * Two things enforce that here. The strings live in one resource file, so neither surface can invent
 * wording. And both mappings sit side by side below, so a state added to one and forgotten in the
 * other is visible in a single screen of code rather than discovered on a device at 3 a.m.
 *
 * The wording itself is verbatim from docs/design-spec.md.
 */
object MonitorCopy {

    // ── The screen ───────────────────────────────────────────────────────────────────────────────

    @StringRes
    fun title(screen: Screen): Int = when (screen) {
        Screen.PermissionMissing -> R.string.permission_title
        Screen.NoHeadphones, Screen.Idle -> R.string.state_idle_title
        Screen.Starting -> R.string.state_starting_title
        Screen.Listening -> R.string.state_listening_title
        is Screen.Paused -> R.string.state_paused_title
        is Screen.Stopped -> R.string.state_stopped_title
    }

    /**
     * The 19 sp line that states the cause, where there is one. Null for the states that need no
     * explanation - deliberately not an empty string, so the layout drops the slot rather than
     * leaving a gap.
     */
    @StringRes
    fun reason(screen: Screen): Int? = when (screen) {
        is Screen.Paused -> when (screen.reason) {
            PauseReason.BluetoothGone -> R.string.paused_headphones_reason
            PauseReason.Call -> R.string.paused_call_reason
            PauseReason.AudioFocusLost -> R.string.paused_audio_reason
            PauseReason.MicPreempted -> R.string.paused_mic_reason
        }
        is Screen.Stopped -> when (screen.kind) {
            ErrorKind.AudioOpenFailed -> R.string.stopped_audio_reason
            ErrorKind.WrongRoute -> R.string.stopped_route_reason
        }
        else -> null
    }

    /**
     * What happens next, or what to do. Every state has one - the screen would read inconsistently
     * if the one state you actually sit and look at were the only one without a line.
     *
     * It briefly did not: while listening, the text ended up inside a scrolling box. The cause was
     * the diagnostics block below it, not this paragraph, and removing that reclaimed the height.
     */
    @StringRes
    fun body(screen: Screen): Int = when (screen) {
        Screen.PermissionMissing -> R.string.permission_body
        Screen.NoHeadphones -> R.string.state_idle_no_headphones_body
        Screen.Idle -> R.string.state_idle_body
        Screen.Starting -> R.string.state_starting_body
        Screen.Listening -> R.string.state_listening_body
        is Screen.Paused -> when (screen.reason) {
            PauseReason.BluetoothGone -> R.string.paused_headphones_body
            PauseReason.Call -> R.string.paused_call_body
            PauseReason.AudioFocusLost -> R.string.paused_audio_body
            PauseReason.MicPreempted -> R.string.paused_mic_body
        }
        is Screen.Stopped -> R.string.stopped_body
    }

    // ── The notification ─────────────────────────────────────────────────────────────────────────

    /**
     * The title carries the reason, because a collapsed notification often shows the title alone -
     * so "Paused" on its own would be exactly the sort of half-message this product cannot afford.
     */
    @StringRes
    fun notificationTitle(state: MonitorState): Int = when (state) {
        MonitorState.Idle -> R.string.notif_idle_title
        MonitorState.Starting -> R.string.notif_starting_title
        is MonitorState.Monitoring -> R.string.notif_listening_title
        is MonitorState.Paused -> when (state.reason) {
            PauseReason.BluetoothGone -> R.string.notif_paused_headphones_title
            PauseReason.Call -> R.string.notif_paused_call_title
            PauseReason.AudioFocusLost -> R.string.notif_paused_audio_title
            PauseReason.MicPreempted -> R.string.notif_paused_mic_title
        }
        is MonitorState.Error -> when (state.kind) {
            ErrorKind.AudioOpenFailed -> R.string.notif_stopped_audio_title
            ErrorKind.WrongRoute -> R.string.notif_stopped_route_title
        }
    }

    @StringRes
    fun notificationText(state: MonitorState): Int = when (state) {
        MonitorState.Idle -> R.string.notif_idle_text
        MonitorState.Starting -> R.string.notif_starting_text
        is MonitorState.Monitoring -> R.string.notif_listening_text
        is MonitorState.Paused -> when (state.reason) {
            PauseReason.BluetoothGone -> R.string.notif_paused_headphones_text
            PauseReason.Call -> R.string.notif_paused_call_text
            PauseReason.AudioFocusLost -> R.string.notif_paused_audio_text
            PauseReason.MicPreempted -> R.string.notif_paused_mic_text
        }
        is MonitorState.Error -> R.string.notif_stopped_text
    }
}
