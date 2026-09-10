package com.yputria.remoteear.ui

import com.yputria.remoteear.monitor.ErrorKind
import com.yputria.remoteear.monitor.MonitorState
import com.yputria.remoteear.monitor.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording is the safety feature, so the mapping to it is worth a test.
 *
 * Kotlin already stops the obvious mistake: `when` over a sealed type or an enum is exhaustive at
 * compile time, so a state added and forgotten will not build. What the compiler cannot catch is
 * **the wrong string in the right slot** — two reasons pointing at one resource, a pause reason
 * wired to another reason's sentence, a notification title that silently duplicates its neighbour.
 * Those are copy-paste errors, and this file is eight near-identical `when` branches, which is
 * precisely where copy-paste errors live.
 *
 * The distinctness assertions are the point. A user who is told "another app is using the
 * microphone" during a phone call has been given a false explanation, and it would have looked
 * perfectly fine in review.
 */
class MonitorCopyTest {

    private val allPauseReasons = PauseReason.entries
    private val allErrorKinds = ErrorKind.entries

    @Test
    fun `every pause reason gets its own sentence on screen`() {
        val ids = allPauseReasons.map { MonitorCopy.reason(Screen.Paused(it)) }
        ids.forEach { assertNotNull("a pause reason has no sentence", it) }
        assertEquals("two pause reasons share a sentence", ids.size, ids.distinct().size)
    }

    @Test
    fun `every pause reason gets its own advice on screen`() {
        val ids = allPauseReasons.map { MonitorCopy.body(Screen.Paused(it)) }
        assertEquals("two pause reasons share their advice", ids.size, ids.distinct().size)
    }

    @Test
    fun `every pause reason gets its own notification title and text`() {
        val titles = allPauseReasons.map {
            MonitorCopy.notificationTitle(MonitorState.Paused(it))
        }
        val texts = allPauseReasons.map { MonitorCopy.notificationText(MonitorState.Paused(it)) }
        assertEquals("two pause reasons share a notification title", titles.size, titles.distinct().size)
        assertEquals("two pause reasons share a notification text", texts.size, texts.distinct().size)
    }

    /**
     * A wrong route means the *earbud's* microphone would have been live - the failure
     * docs/adr/0004-media-path-only.md exists to prevent. It must never be worded as an ordinary
     * audio failure.
     */
    @Test
    fun `the two error kinds are never described the same way`() {
        val screen = allErrorKinds.map { MonitorCopy.reason(Screen.Stopped(it, "detail")) }
        val titles = allErrorKinds.map {
            MonitorCopy.notificationTitle(MonitorState.Error("detail", it))
        }
        assertNotEquals("both error kinds share one sentence", screen[0], screen[1])
        assertNotEquals("both error kinds share one notification title", titles[0], titles[1])
    }

    @Test
    fun `only paused and stopped screens carry a reason line`() {
        val withReason = listOf(
            Screen.Paused(PauseReason.Call),
            Screen.Stopped(ErrorKind.WrongRoute, "detail"),
        )
        val withoutReason = listOf(
            Screen.Idle,
            Screen.Starting,
            Screen.Listening,
            Screen.NoHeadphones,
            Screen.PermissionMissing,
        )
        withReason.forEach { assertNotNull(MonitorCopy.reason(it)) }
        withoutReason.forEach {
            assertEquals("$it should have no reason line", null, MonitorCopy.reason(it))
        }
    }

    @Test
    fun `every screen has a title and a body`() {
        val screens = listOf(
            Screen.Idle,
            Screen.Starting,
            Screen.Listening,
            Screen.NoHeadphones,
            Screen.PermissionMissing,
            Screen.Paused(PauseReason.BluetoothGone),
            Screen.Paused(PauseReason.Call),
            Screen.Paused(PauseReason.AudioFocusLost),
            Screen.Paused(PauseReason.MicPreempted),
            Screen.Stopped(ErrorKind.AudioOpenFailed, "detail"),
            Screen.Stopped(ErrorKind.WrongRoute, "detail"),
        )
        screens.forEach {
            assertTrue("$it has no title", MonitorCopy.title(it) != 0)
            assertTrue("$it has no body", MonitorCopy.body(it) != 0)
        }
    }

    /**
     * Idle and "no headphones" deliberately share a *title* - both are "Not listening" - but the
     * body differs, because one is ready to go and the other is telling you what is missing.
     */
    @Test
    fun `no headphones reads as not listening but explains itself differently`() {
        assertEquals(MonitorCopy.title(Screen.Idle), MonitorCopy.title(Screen.NoHeadphones))
        assertNotEquals(MonitorCopy.body(Screen.Idle), MonitorCopy.body(Screen.NoHeadphones))
    }
}
