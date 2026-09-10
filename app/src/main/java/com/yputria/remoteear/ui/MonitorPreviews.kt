package com.yputria.remoteear.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.yputria.remoteear.monitor.ErrorKind
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.monitor.PauseReason
import com.yputria.remoteear.theme.RemoteEarTheme

/**
 * Every state in the machine, rendered.
 *
 * This file is the Phase 4 gate, not decoration. The gate reads: *every state is reachable and
 * correctly rendered, including each `Paused` reason* - and three of those four reasons cannot be
 * produced on demand on a device. Waiting for a phone call to arrive while checking that the amber
 * badge and the right sentence appear is not a test, it is a coincidence.
 *
 * Because [MonitorScreen] is a pure function of [MonitorUiState], each state below is a literal.
 * Phase 5 wires the real triggers; what these previews establish is that when a trigger fires, the
 * screen it produces already exists and says the right thing.
 */
private fun state(
    screen: Screen,
    micStatus: MicStatus = MicStatus.Allowed,
    headphones: HeadphoneStatus = HeadphoneStatus.Connected("Buds Pro"),
    volume: Float = 0.57f,
    noise: Float = 0f,
    endedUnexpectedly: Boolean = false,
) = MonitorUiState(
    screen = screen,
    micStatus = micStatus,
    headphones = headphones,
    volume = volume,
    noiseReduction = noise,
    endedUnexpectedly = endedUnexpectedly,
    inputSource = InputSource.Mic,
    unprocessedSupported = true,
    routedIn = "BUILTIN_MIC",
    routedOut = "BLUETOOTH_A2DP",
    counters = null,
)

@Composable
private fun Rendered(state: MonitorUiState, dimmed: Boolean = false, dark: Boolean = true) {
    RemoteEarTheme(darkTheme = dark) {
        MonitorScreen(state = state, dimmed = dimmed)
    }
}

private const val PHONE = "spec:width=412dp,height=892dp"

@Preview(name = "01 Idle", device = PHONE)
@Composable
private fun PreviewIdle() = Rendered(state(Screen.Idle))

@Preview(name = "02 Starting", device = PHONE)
@Composable
private fun PreviewStarting() = Rendered(state(Screen.Starting))

@Preview(name = "03 Listening", device = PHONE)
@Composable
private fun PreviewListening() =
    Rendered(state(Screen.Listening, micStatus = MicStatus.InUseByUs, noise = 0.25f))

@Preview(name = "04 Listening, dimmed", device = PHONE)
@Composable
private fun PreviewListeningDimmed() =
    Rendered(state(Screen.Listening, micStatus = MicStatus.InUseByUs), dimmed = true)

@Preview(name = "05 Paused, headphones gone", device = PHONE)
@Composable
private fun PreviewPausedHeadphones() = Rendered(
    state(
        Screen.Paused(PauseReason.BluetoothGone),
        headphones = HeadphoneStatus.Disconnected,
        noise = 0.25f,
    ),
)

@Preview(name = "06 Paused, phone call", device = PHONE)
@Composable
private fun PreviewPausedCall() = Rendered(
    state(Screen.Paused(PauseReason.Call), micStatus = MicStatus.UsedByCall, noise = 0.25f),
)

@Preview(name = "07 Paused, audio taken", device = PHONE)
@Composable
private fun PreviewPausedAudio() = Rendered(
    state(
        Screen.Paused(PauseReason.AudioFocusLost),
        headphones = HeadphoneStatus.BusyElsewhere,
        noise = 0.25f,
    ),
)

@Preview(name = "08 Paused, microphone taken", device = PHONE)
@Composable
private fun PreviewPausedMic() = Rendered(
    state(
        Screen.Paused(PauseReason.MicPreempted),
        micStatus = MicStatus.UsedByOtherApp,
        noise = 0.25f,
    ),
)

@Preview(name = "09 Stopped, audio open failed", device = PHONE)
@Composable
private fun PreviewStoppedAudio() = Rendered(
    state(
        Screen.Stopped(
            ErrorKind.AudioOpenFailed,
            "AudioRecord.build failed: java.lang.UnsupportedOperationException",
        ),
    ),
)

/** The failure ADR-0004 exists to prevent. It must never render as a resumable pause. */
@Preview(name = "10 Stopped, wrong route", device = PHONE)
@Composable
private fun PreviewStoppedRoute() = Rendered(
    state(
        Screen.Stopped(
            ErrorKind.WrongRoute,
            "output routed to BLUETOOTH_SCO - the link collapsed to narrowband hands-free",
        ),
    ),
)

@Preview(name = "11 Permission not granted", device = PHONE)
@Composable
private fun PreviewPermission() =
    Rendered(state(Screen.PermissionMissing, micStatus = MicStatus.Denied))

@Preview(name = "12 No headphones", device = PHONE)
@Composable
private fun PreviewNoHeadphones() =
    Rendered(state(Screen.NoHeadphones, headphones = HeadphoneStatus.None))

/** The S5 notice: a report about a past session, which must not read as the current state. */
@Preview(name = "13 Ended unexpectedly", device = PHONE)
@Composable
private fun PreviewEndedUnexpectedly() = Rendered(state(Screen.Idle, endedUnexpectedly = true))

@Preview(name = "14 Idle, light theme", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PreviewIdleLight() = Rendered(state(Screen.Idle), dark = false)

@Preview(name = "15 Listening, light theme", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PreviewListeningLight() = Rendered(
    state(Screen.Listening, micStatus = MicStatus.InUseByUs, noise = 0.8f),
    dark = false,
)

@Preview(name = "16 Paused, light theme", device = PHONE, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PreviewPausedLight() = Rendered(
    state(Screen.Paused(PauseReason.BluetoothGone), headphones = HeadphoneStatus.Disconnected),
    dark = false,
)
