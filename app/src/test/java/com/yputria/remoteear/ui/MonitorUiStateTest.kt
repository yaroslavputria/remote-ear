package com.yputria.remoteear.ui

import com.yputria.remoteear.monitor.ErrorKind
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.monitor.PauseReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which controls appear, and when.
 *
 * These are one-line properties, which is exactly why they are worth pinning: they encode product
 * decisions that took a conversation to reach and would be trivial to "simplify" away by someone
 * reading only the code.
 */
class MonitorUiStateTest {

    private fun state(screen: Screen) = MonitorUiState(
        screen = screen,
        micStatus = MicStatus.Allowed,
        headphones = HeadphoneStatus.Connected("Buds"),
        volume = 0.5f,
        noiseReduction = 0f,
        endedUnexpectedly = false,
        inputSource = InputSource.Mic,
        unprocessedSupported = true,
    )

    @Test
    fun `the volume readout is hidden whenever audio is not flowing`() {
        // Showing a level while paused would be answering a question nobody is asking - and worse,
        // it would look like a sign of life.
        assertFalse(state(Screen.Paused(PauseReason.Call)).showVolume)
        assertFalse(state(Screen.Stopped(ErrorKind.AudioOpenFailed, "d")).showVolume)
        assertFalse(state(Screen.PermissionMissing).showVolume)
        assertFalse(state(Screen.NoHeadphones).showVolume)

        assertTrue(state(Screen.Idle).showVolume)
        assertTrue(state(Screen.Listening).showVolume)
    }

    @Test
    fun `noise reduction stays adjustable while paused`() {
        // So it can be set before listening resumes, rather than only during.
        PauseReason.entries.forEach {
            assertTrue("paused for $it", state(Screen.Paused(it)).showNoiseReduction)
        }
        assertFalse(state(Screen.Stopped(ErrorKind.WrongRoute, "d")).showNoiseReduction)
        assertFalse(state(Screen.PermissionMissing).showNoiseReduction)
    }

    @Test
    fun `noise reduction only looks live while audio is actually flowing`() {
        assertTrue(state(Screen.Listening).noiseReductionIsLive)
        assertFalse(state(Screen.Idle).noiseReductionIsLive)
        assertFalse(state(Screen.Paused(PauseReason.BluetoothGone)).noiseReductionIsLive)
    }

    /**
     * Paused counts as active. The service is alive, the session is the user's, and the control has
     * to read "Stop listening" - offering "Listen" while a session is merely paused would suggest
     * nothing is running.
     */
    @Test
    fun `a paused monitor is still an active session`() {
        assertTrue(state(Screen.Starting).isActive)
        assertTrue(state(Screen.Listening).isActive)
        PauseReason.entries.forEach { assertTrue(state(Screen.Paused(it)).isActive) }

        assertFalse(state(Screen.Idle).isActive)
        assertFalse(state(Screen.NoHeadphones).isActive)
        assertFalse(state(Screen.PermissionMissing).isActive)
        assertFalse(state(Screen.Stopped(ErrorKind.AudioOpenFailed, "d")).isActive)
    }
}
