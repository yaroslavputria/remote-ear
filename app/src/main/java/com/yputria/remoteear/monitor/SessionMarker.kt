package com.yputria.remoteear.monitor

import android.content.Context

private const val PREFS = "remote_ear_session"
private const val KEY_ACTIVE = "session_active"
private const val KEY_UNACKNOWLEDGED = "ended_unexpectedly"

/**
 * Remembers, across process death, that a listening session was in progress.
 *
 * This is the whole of the [R1](../../../../../../docs/risks.md) mitigation — the risk ranked first
 * in the project — and until 2026-09-10 it did not work in the case that matters.
 *
 * The flag used to be a static field set from `Service.onDestroy()`. Two things wrong with that, and
 * the evidence for both is in
 * [the Scenario G run](../../../../../../docs/test-runs/2026-09-10-oneplus-cph2399-scenario-g.md):
 *
 *  1. An OEM battery manager that **kills the process outright never calls `onDestroy()`**, so the
 *     flag was never set. ColorOS was observed freezing this app within 50 ms of the screen going
 *     off; a kill is the same mechanism carried one step further.
 *  2. Even when `onDestroy()` did run, the flag lived in memory that died with the process moments
 *     later.
 *
 * So the only shape of R1 it ever caught was "the service was destroyed but the process survived" —
 * the least likely one. A user whose monitor was killed at 3 a.m. would have opened the app to a
 * clean Idle screen with nothing to suggest anything had gone wrong. For a product whose one
 * promise is that it tells you when it has stopped listening, that is the worst possible failure.
 *
 * The marker is set when a session begins and cleared only when the user deliberately stops. If it
 * is still set when the UI next starts and nothing is running, the last session ended without
 * anyone asking it to — and that is worth saying out loud.
 *
 * It stores two booleans. No audio, no counters, nothing about what was heard — see invariant 4.
 */
class SessionMarker(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A session has begun. Survives a kill, which is the point. */
    fun sessionStarted() {
        prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
    }

    /**
     * The session ended and **the user was told** — either they stopped it, or it failed into an
     * error state that says so on screen and in the notification.
     *
     * Both count as clean endings, because the marker exists to catch endings nobody was told
     * about. Clearing it only on a deliberate stop would raise "listening stopped on its own" after
     * every visible failure, and a warning that cries wolf is worse than no warning.
     */
    fun sessionEnded() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    /**
     * Turns a stale marker into a notice, and returns whether one is outstanding.
     *
     * [serviceRunning] must be the truth at the moment of asking: if a session is genuinely still
     * in progress, a set marker means everything is fine.
     */
    fun reconcile(serviceRunning: Boolean): Boolean {
        if (!serviceRunning && prefs.getBoolean(KEY_ACTIVE, false)) {
            prefs.edit()
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_UNACKNOWLEDGED, true)
                .apply()
        }
        return prefs.getBoolean(KEY_UNACKNOWLEDGED, false)
    }

    fun acknowledge() {
        prefs.edit().putBoolean(KEY_UNACKNOWLEDGED, false).apply()
    }
}
