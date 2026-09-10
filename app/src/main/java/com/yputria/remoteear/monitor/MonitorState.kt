package com.yputria.remoteear.monitor

/**
 * The monitoring state machine from docs/audio-pipeline.md.
 *
 * The design rule this type exists to enforce: **a paused monitor must look paused.** The worst
 * failure this product can have is a user believing they are listening to their child when they are
 * not, so [Paused] always carries a [PauseReason] and that reason is rendered in both the UI and the
 * notification. There is deliberately no state that means "stopped, quietly".
 */
sealed interface MonitorState {

    /** Not monitoring. The state on a cold start. */
    data object Idle : MonitorState

    /** Streams opening and routing being verified. Brief, but real, and worth showing. */
    data object Starting : MonitorState

    /** Audio is flowing to the headphones. */
    data class Monitoring(
        val inputSource: InputSource,
        val routedInType: Int,
        val routedOutType: Int,
    ) : MonitorState

    /**
     * Audio is not flowing, the service is still alive, and the user is told why.
     *
     * The service surviving this state is what makes automatic resume possible at all: Android 14+
     * forbids *starting* a microphone foreground service from the background, but places no such
     * restriction on one already running. See docs/adr/0005-foreground-service-hosts-monitoring.md.
     */
    data class Paused(val reason: PauseReason) : MonitorState

    /** Something is wrong and the user must act. Carries what was actually observed. */
    data class Error(
        val message: String,
        val kind: ErrorKind = ErrorKind.AudioOpenFailed,
    ) : MonitorState
}

/**
 * What kind of failure to *tell the user about*. [MonitorState.Error.message] stays technical and
 * goes in the diagnostic row; this decides the sentence above it.
 *
 * The distinction is not cosmetic. "The microphone could not be opened" and "your headphones
 * connected as a headset" call for different actions from the user, and the second one means the
 * headset microphone would have been live instead of the phone's - the failure
 * docs/adr/0004-media-path-only.md exists to prevent. Reporting it as a generic audio error would
 * hide exactly the thing worth knowing.
 */
enum class ErrorKind {
    /** A stream would not open, or the platform rejected the format. */
    AudioOpenFailed,

    /** `getRoutedDevice()` disagreed with what we asked for - including any `TYPE_BLUETOOTH_SCO`. */
    WrongRoute,
}

/**
 * Why monitoring is paused. Each needs its own user-visible wording - see docs/design-brief.md.
 * Phase 5 wires the triggers; Phase 3 only ever reaches [BluetoothGone] via a failed start.
 */
enum class PauseReason {
    /** The last usable Bluetooth sink went away. Resumes by itself on reconnect. */
    BluetoothGone,

    /** Another app took audio focus transiently. Resumes on regain. */
    AudioFocusLost,

    /** A call is in progress. We never touch call audio; we get out of the way. */
    Call,

    /**
     * Another app holds the microphone. Detected via `isClientSilenced()`, because the platform
     * delivers *silence* rather than an error - and in a monitor, silence is indistinguishable
     * from a quiet room. See docs/risks.md R7.
     */
    MicPreempted,
}
