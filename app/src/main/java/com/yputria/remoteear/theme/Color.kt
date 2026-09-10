package com.yputria.remoteear.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The palette from docs/design-spec.md, verbatim.
 *
 * Material 3's `ColorScheme` has no slot for "paused", and RemoteEar needs three *state* colours
 * that are never used decoratively: teal for listening, amber for paused, red for stopped. So the
 * palette is its own type, passed down through [com.yputria.remoteear.theme.LocalPalette].
 *
 * Two rules the values encode, both from the design:
 *
 * - **Contrast ratios are load-bearing**, not incidental. Every colour here was chosen against its
 *   own surface (7.1:1 for supporting text, 10.4:1 for listening) so the screen stays readable at
 *   the lowest system brightness, which is where this app is actually used.
 * - **Colour is never the only signal.** Teal and amber collapse toward each other under
 *   deuteranopia, so each state also has its own shape - see `StateBadge`.
 *
 * Dynamic colour is deliberately not used: it would let the system repaint "listening" and "paused"
 * as neighbouring pastels, destroying the distinction the design exists to guarantee.
 */
@Immutable
data class RemoteEarPalette(
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val statusLabel: Color,
    val footnote: Color,
    val wordmark: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val paused: Color,
    val error: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val neutral: Color,
    val disabledContainer: Color,
    val onDisabled: Color,
    val trackInactive: Color,
    /** The dimmed listening screen. Black surface, ~5:1 state text, ~4.6:1 Stop. */
    val dimSurface: Color,
    val dimPrimary: Color,
    val dimPrimaryQuiet: Color,
    val dimOnSurface: Color,
    val dimOutline: Color,
    val dimWordmark: Color,
)

/** Dark is the primary appearance: this app is opened in a dark room by a tired parent. */
val DarkPalette = RemoteEarPalette(
    surface = Color(0xFF0E1112),
    surfaceContainer = Color(0xFF151A1B),
    surfaceContainerHigh = Color(0xFF232B2C),
    onSurface = Color(0xFFE6EAE9),
    onSurfaceVariant = Color(0xFF9AA3A2),
    statusLabel = Color(0xFFC9D0CF),
    footnote = Color(0xFF6E7877),
    wordmark = Color(0xFF5E6867),
    outline = Color(0xFF48524F),
    primary = Color(0xFF6FD3B8),
    onPrimary = Color(0xFF04211B),
    paused = Color(0xFFF2B45C),
    error = Color(0xFFFF9A8F),
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Color(0xFFFFC2BA),
    neutral = Color(0xFF8C9694),
    disabledContainer = Color(0xFF1B2223),
    onDisabled = Color(0xFF7C8685),
    trackInactive = Color(0xFF2A3132),
    dimSurface = Color(0xFF000000),
    dimPrimary = Color(0xFF3E8C7D),
    dimPrimaryQuiet = Color(0xFF2F776A),
    dimOnSurface = Color(0xFF5C6564),
    dimOutline = Color(0xFF262C2C),
    dimWordmark = Color(0xFF2A2F2E),
)

/** The light mirror. Same roles, same shapes, ratios re-measured against a light surface. */
val LightPalette = RemoteEarPalette(
    surface = Color(0xFFF7FAF9),
    surfaceContainer = Color(0xFFEBF0EE),
    surfaceContainerHigh = Color(0xFFDDE4E2),
    onSurface = Color(0xFF171D1C),
    onSurfaceVariant = Color(0xFF4A5453),
    statusLabel = Color(0xFF2B3231),
    footnote = Color(0xFF5C6564),
    wordmark = Color(0xFF7C8685),
    outline = Color(0xFFB7C0BE),
    primary = Color(0xFF00695B),
    onPrimary = Color(0xFFFFFFFF),
    paused = Color(0xFF7A4A00),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
    neutral = Color(0xFF5C6564),
    disabledContainer = Color(0xFFDDE4E2),
    onDisabled = Color(0xFF6F7977),
    trackInactive = Color(0xFFC8D0CE),
    // The dim state stays dark even in the light theme: its whole purpose is emitting less light
    // into a room where someone is asleep.
    dimSurface = Color(0xFF000000),
    dimPrimary = Color(0xFF3E8C7D),
    dimPrimaryQuiet = Color(0xFF2F776A),
    dimOnSurface = Color(0xFF5C6564),
    dimOutline = Color(0xFF262C2C),
    dimWordmark = Color(0xFF2A2F2E),
)
