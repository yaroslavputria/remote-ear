package com.yputria.remoteear.ui

import androidx.compose.runtime.Immutable
import com.yputria.remoteear.monitor.ErrorKind
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.monitor.PauseReason

/**
 * Which of the designed screens to render.
 *
 * This is *not* a duplicate of `MonitorState`. That type models what the audio pipeline is doing;
 * this models what the user is looking at, and the two differ in a way that matters: "no microphone
 * permission" and "no headphones connected" are full screens in the design
 * (docs/design-spec.md) but are not pipeline states at all - the pipeline has never been asked to
 * start. Collapsing them into `Idle` would lose the explanation the user needs, and the Phase 4 gate
 * is explicit that denying permission must produce an explanation rather than a dead button.
 */
sealed interface Screen {

    /** RECORD_AUDIO not granted. Rationale plus the request, before the system dialog. */
    data object PermissionMissing : Screen

    /** Permission is fine, but there is nothing to play to. The control says why. */
    data object NoHeadphones : Screen

    /** Ready. The default on open. */
    data object Idle : Screen

    /** Streams opening, routing being verified. Brief, but real - the control goes inert, not away. */
    data object Starting : Screen

    /** Audio is flowing. */
    data object Listening : Screen

    /** Audio is not flowing, and the reason is on the screen. */
    data class Paused(val reason: PauseReason) : Screen

    /** Stopped and needing the user. [detail] is the technical line for the diagnostic row. */
    data class Stopped(val kind: ErrorKind, val detail: String) : Screen
}

/** The microphone status row. Four values, because "allowed" and "in use" are different facts. */
enum class MicStatus { Allowed, Denied, InUseByUs, UsedByCall, UsedByOtherApp }

/** The headphone status row. Never says "connected" when audio is not flowing. */
sealed interface HeadphoneStatus {
    /**
     * [name] is the headphones' own name where the platform gives one, and null where it does not -
     * in which case the row reads "Bluetooth headphones". The device *type* never appears here; it
     * is a debug string, and `BLUETOOTH_A2DP` is not something to show a tired parent at 3 a.m.
     */
    data class Connected(val name: String?) : HeadphoneStatus
    data object None : HeadphoneStatus
    data object Disconnected : HeadphoneStatus
    data object BusyElsewhere : HeadphoneStatus
}

@Immutable
data class MonitorUiState(
    val screen: Screen,
    val micStatus: MicStatus,
    val headphones: HeadphoneStatus,
    /** 0..1 fraction of the phone's media volume. Displayed, never set - see ADR-0007. */
    val volume: Float,
    /** 0..1. Minimum means off, which is the whole point of having one control. */
    val noiseReduction: Float,
    /** A previous session ended without the user stopping it (risks.md R1). */
    val endedUnexpectedly: Boolean,
    val inputSource: InputSource,
    val unprocessedSupported: Boolean,
    val routedIn: String? = null,
    val routedOut: String? = null,
    val counters: String? = null,
) {
    /** The service is alive and doing something. */
    val isActive: Boolean
        get() = screen is Screen.Starting || screen is Screen.Listening || screen is Screen.Paused

    /**
     * Volume is only shown where it answers a real question. When audio is not flowing, a volume
     * readout would be answering one nobody is asking - and worse, it would look like a sign of
     * life.
     */
    val showVolume: Boolean
        get() = screen is Screen.Idle || screen is Screen.Starting || screen is Screen.Listening

    /** Noise reduction stays adjustable while paused, so it is set before listening resumes. */
    val showNoiseReduction: Boolean
        get() = showVolume || screen is Screen.Paused

    /** Teal only while audio is actually flowing. Elsewhere the control is neutral, not disabled. */
    val noiseReductionIsLive: Boolean
        get() = screen is Screen.Listening
}
