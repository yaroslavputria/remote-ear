package com.yputria.remoteear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yputria.remoteear.R
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.theme.LocalPalette
import com.yputria.remoteear.theme.RemoteEarPalette
import kotlinx.coroutines.delay

/** After this long without a touch, the listening screen goes dark. See docs/design-spec.md. */
private const val DIM_AFTER_MS = 20_000L

/** The design's volume readout: seven segments, no thumb, because it is a reading not a control. */
private const val VOLUME_SEGMENTS = 7

/**
 * The screen, wired to the [MonitorViewModel].
 *
 * Everything stateful lives here; [MonitorScreen] below is a pure function of its arguments, which
 * is what makes every state - including all four pause reasons - reachable from a `@Preview` instead
 * of only from a device with a phone call arriving at the right moment.
 */
@Composable
fun MonitorRoute(
    viewModel: MonitorViewModel,
    onRequestMicPermission: () -> Unit,
    onMenu: (MenuItem) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Any touch wakes the screen. Counting touches rather than timestamping them keeps this
    // testable and avoids a clock read on every frame.
    var touches by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }

    // Restarts on every touch and on every state change, so a pause always brings the screen back
    // to full brightness: a paused monitor must look paused, and it cannot do that in the dark.
    LaunchedEffect(state.screen, touches) {
        dimmed = false
        if (state.screen is Screen.Listening) {
            delay(DIM_AFTER_MS)
            dimmed = true
        }
    }

    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // While dimmed, the first touch only brightens. This is the design's
                    // behaviour and it also stops a blind tap in the dark from hitting Stop.
                    if (dimmed) event.changes.forEach { it.consume() }
                    touches++
                }
            }
        },
    ) {
        MonitorScreen(
            state = state,
            dimmed = dimmed,
            onListen = viewModel::listen,
            onStop = viewModel::stop,
            onAllowMic = onRequestMicPermission,
            onNoiseReduction = viewModel::setNoiseReduction,
            onDismissNotice = viewModel::dismissUnexpectedEnd,
            onSelectInput = viewModel::selectInputSource,
            onMenu = onMenu,
        )
    }
}

@Composable
fun MonitorScreen(
    state: MonitorUiState,
    dimmed: Boolean = false,
    onListen: () -> Unit = {},
    onStop: () -> Unit = {},
    onAllowMic: () -> Unit = {},
    onNoiseReduction: (Float) -> Unit = {},
    onDismissNotice: () -> Unit = {},
    onSelectInput: (InputSource) -> Unit = {},
    onMenu: (MenuItem) -> Unit = {},
) {
    val palette = LocalPalette.current
    val background = if (dimmed) palette.dimSurface else palette.surface

    // Off by default and reachable by long-pressing the wordmark. It is a testing affordance, not a
    // feature, and on screen it cost height the designed layout did not have to spare.
    var diagnosticsVisible by remember { mutableStateOf(false) }

    // Fixed layout rather than a scrolling page: the control belongs at the bottom, under the
    // thumb, and the state block fills what is left. Only the state block scrolls, so nothing
    // becomes unreachable on a short screen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 6.dp, bottom = 12.dp),
    ) {
        // The wordmark row carries the overflow menu. It is the only horizontal space on the screen
        // that was already there, so the documents cost no height at all - and while dimmed the
        // menu disappears with everything else that is not state or Stop.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.wordmark),
                fontSize = 11.sp,
                letterSpacing = 2.6.sp,
                color = if (dimmed) palette.dimWordmark else palette.wordmark,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { diagnosticsVisible = !diagnosticsVisible },
                        )
                    },
            )
            if (!dimmed) OverflowMenu(onSelect = onMenu)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            StateBlock(state.screen, dimmed)
        }

        PrimaryControl(state.screen, dimmed, state.micPermanentlyDenied, onListen, onStop, onAllowMic)

        // Nothing below the control is drawn while dimmed. State and Stop never fade; everything
        // else does, because light in a room where someone is asleep is a cost.
        if (dimmed) return@Column

        val stopped = state.screen as? Screen.Stopped
        if (state.screen != Screen.PermissionMissing) {
            Spacer(Modifier.height(18.dp))
            StatusPanel(
                micStatus = state.micStatus,
                headphones = state.headphones,
                errorDetail = stopped?.detail,
                endedUnexpectedly = state.endedUnexpectedly,
                onDismissNotice = onDismissNotice,
            )
        }

        if (state.showVolume || state.showNoiseReduction) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.showVolume) VolumeReadout(state.volume)
                if (state.showNoiseReduction) {
                    NoiseReduction(state.noiseReduction, state.noiseReductionIsLive, onNoiseReduction)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(
                if (state.screen == Screen.PermissionMissing) {
                    R.string.permission_footnote
                } else {
                    R.string.privacy_footnote
                },
            ),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.footnote,
        )

        if (diagnosticsVisible) {
            Spacer(Modifier.height(12.dp))
            Diagnostics(state, onSelectInput)
        }
    }
}

// ── The state block ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun StateBlock(screen: Screen, dimmed: Boolean) {
    val palette = LocalPalette.current
    val listening = screen is Screen.Listening

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (screen is Screen.Paused) 20.dp else 22.dp),
        modifier = Modifier.padding(vertical = 12.dp),
    ) {
        StateBadge(screen, dimmed)

        Text(
            text = stringResource(MonitorCopy.title(screen)),
            fontSize = if (listening || screen is Screen.Paused || screen is Screen.Stopped) 34.sp else 30.sp,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center,
            color = when {
                dimmed -> palette.dimPrimary
                else -> screen.accentOr(palette, palette.onSurface)
            },
        )

        if (dimmed) {
            Text(
                text = stringResource(R.string.state_listening_dim_body),
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                color = palette.dimOnSurface,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            return@Column
        }

        MonitorCopy.reason(screen)?.let { reason ->
            Text(
                text = stringResource(reason),
                fontSize = 19.sp,
                lineHeight = 27.sp,
                textAlign = TextAlign.Center,
                color = palette.onSurface,
                modifier = Modifier.widthIn(max = 310.dp),
            )
        }

        Text(
            text = stringResource(MonitorCopy.body(screen)),
            fontSize = 16.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = palette.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 310.dp),
        )
    }
}

/**
 * The state, as a shape.
 *
 * Teal and amber collapse toward each other under deuteranopia, and both wash out at the lowest
 * screen brightness, so **no state is identified by colour alone**: idle is a hollow ring, starting
 * is dashed, listening is two rings around a large filled centre, paused is two bars, stopped is a
 * filled disc with a mark, and a missing permission is a ring struck through.
 *
 * It carries no content description. The state name sits directly beneath it at 30-34 sp, so
 * announcing the shape as well would only repeat what the screen reader is about to read out.
 */
@Composable
private fun StateBadge(screen: Screen, dimmed: Boolean) {
    val palette = LocalPalette.current
    val big = screen is Screen.Listening && !dimmed
    val accent = if (dimmed) palette.dimPrimaryQuiet else screen.accentOr(palette, palette.neutral)

    Box(
        modifier = Modifier.size(if (big) 96.dp else 72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val outer = size.minDimension / 2f - stroke / 2f

            when (screen) {
                Screen.PermissionMissing -> {
                    drawCircle(accent, radius = outer, style = Stroke(stroke))
                    // Struck through, clipped to the ring so the line cannot read as a separate mark.
                    clipPath(Path().apply { addOval(Rect(center = center, radius = outer)) }) {
                        rotate(-45f) {
                            drawLine(
                                color = accent,
                                start = Offset(0f, center.y),
                                end = Offset(size.width, center.y),
                                strokeWidth = stroke,
                            )
                        }
                    }
                }

                Screen.Idle, Screen.NoHeadphones -> {
                    drawCircle(accent, radius = outer, style = Stroke(stroke))
                    drawCircle(accent, radius = 7.dp.toPx())
                }

                Screen.Starting -> {
                    drawCircle(
                        color = accent,
                        radius = outer,
                        style = Stroke(
                            width = stroke,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(7.dp.toPx(), 6.dp.toPx()),
                                0f,
                            ),
                        ),
                    )
                    drawCircle(accent, radius = 7.dp.toPx())
                }

                Screen.Listening -> {
                    if (!dimmed) {
                        // The quiet outer ring: presence without brightness.
                        drawCircle(
                            color = accent.copy(alpha = 0.35f),
                            radius = outer,
                            style = Stroke(stroke),
                        )
                        drawCircle(accent, radius = 36.dp.toPx() - stroke / 2f, style = Stroke(stroke))
                    } else {
                        drawCircle(accent, radius = outer, style = Stroke(stroke))
                    }
                    drawCircle(accent, radius = 13.dp.toPx())
                }

                is Screen.Paused -> {
                    drawCircle(accent, radius = outer, style = Stroke(stroke))
                    val barWidth = 7.dp.toPx()
                    val barHeight = 28.dp.toPx()
                    val gap = 7.dp.toPx()
                    listOf(-1, 1).forEach { side ->
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset(
                                x = center.x + side * gap / 2f - if (side < 0) barWidth else 0f,
                                y = center.y - barHeight / 2f,
                            ),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                }

                is Screen.Stopped -> {
                    drawCircle(palette.errorContainer, radius = outer)
                    drawCircle(accent, radius = outer, style = Stroke(stroke))
                }
            }
        }

        if (screen is Screen.Stopped) {
            Text(
                text = "!",
                fontSize = 38.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }
    }
}

/** The state colour, or [fallback] for the states that are deliberately colourless. */
private fun Screen.accentOr(palette: RemoteEarPalette, fallback: Color): Color = when (this) {
    Screen.Starting, Screen.Listening -> palette.primary
    is Screen.Paused -> palette.paused
    is Screen.Stopped -> palette.error
    Screen.Idle, Screen.NoHeadphones, Screen.PermissionMissing -> fallback
}

// ── The primary control ──────────────────────────────────────────────────────────────────────────

/**
 * One control, 92 dp tall, at the bottom of the screen.
 *
 * Listen is a **pill**; Stop is a **rounded square with an outline**. That difference is doing real
 * work: this button is pressed in the dark by someone half awake, and shape is legible at a glance
 * in a way that a swapped label is not.
 */
@Composable
private fun PrimaryControl(
    screen: Screen,
    dimmed: Boolean,
    permanentlyDenied: Boolean,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onAllowMic: () -> Unit,
) {
    val palette = LocalPalette.current

    when (screen) {
        Screen.PermissionMissing -> ControlSurface(
            onClick = onAllowMic,
            shape = RoundedCornerShape(46.dp),
            container = palette.primary,
        ) {
            ControlLabel(
                text = stringResource(
                    if (permanentlyDenied) R.string.permission_action_settings
                    else R.string.permission_action,
                ),
                color = palette.onPrimary,
            )
        }

        Screen.Idle, is Screen.Stopped -> ControlSurface(
            onClick = onListen,
            shape = RoundedCornerShape(46.dp),
            container = palette.primary,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.onPrimary),
                )
                ControlLabel(stringResource(R.string.action_listen), palette.onPrimary)
            }
        }

        // Disabled, and it says why. An inert button with no explanation is the thing the Phase 4
        // gate specifically forbids.
        Screen.NoHeadphones -> ControlSurface(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(46.dp),
            container = palette.disabledContainer,
            border = BorderStroke(1.dp, palette.trackInactive),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ControlLabel(stringResource(R.string.action_listen), palette.onDisabled)
                Text(
                    text = stringResource(R.string.action_blocked_headphones),
                    fontSize = 14.sp,
                    color = palette.onDisabled,
                )
            }
        }

        Screen.Starting -> ControlSurface(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(46.dp),
            container = palette.disabledContainer,
        ) {
            ControlLabel(stringResource(R.string.action_starting), palette.onDisabled)
        }

        Screen.Listening, is Screen.Paused -> ControlSurface(
            onClick = onStop,
            shape = RoundedCornerShape(20.dp),
            container = if (dimmed) Color(0xFF0C0F10) else palette.surfaceContainerHigh,
            border = BorderStroke(2.dp, if (dimmed) palette.dimOutline else palette.outline),
        ) {
            val content = if (dimmed) palette.dimOnSurface else palette.onSurface
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(content),
                )
                ControlLabel(stringResource(R.string.action_stop), content)
            }
        }
    }
}

@Composable
private fun ControlSurface(
    onClick: () -> Unit,
    shape: RoundedCornerShape,
    container: Color,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = container,
        contentColor = LocalPalette.current.onSurface,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ControlLabel(text: String, color: Color) {
    Text(text = text, fontSize = 23.sp, fontWeight = FontWeight.Medium, color = color)
}

// ── Status ───────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatusPanel(
    micStatus: MicStatus,
    headphones: HeadphoneStatus,
    errorDetail: String?,
    endedUnexpectedly: Boolean,
    onDismissNotice: () -> Unit,
) {
    val palette = LocalPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surfaceContainer)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val micTone = when (micStatus) {
            MicStatus.Allowed, MicStatus.InUseByUs -> palette.primary
            MicStatus.UsedByCall, MicStatus.UsedByOtherApp -> palette.paused
            MicStatus.Denied -> palette.neutral
        }
        StatusRow(
            dot = micTone,
            label = stringResource(R.string.status_microphone),
            value = stringResource(
                when (micStatus) {
                    MicStatus.Allowed -> R.string.status_mic_allowed
                    MicStatus.Denied -> R.string.status_mic_denied
                    MicStatus.InUseByUs -> R.string.status_mic_in_use
                    MicStatus.UsedByCall -> R.string.status_mic_call
                    MicStatus.UsedByOtherApp -> R.string.status_mic_other_app
                },
            ),
            valueColor = if (micStatus == MicStatus.Allowed || micStatus == MicStatus.InUseByUs) {
                palette.onSurfaceVariant
            } else {
                micTone
            },
        )

        val headphoneTone = when (headphones) {
            is HeadphoneStatus.Connected -> palette.primary
            HeadphoneStatus.Disconnected, HeadphoneStatus.BusyElsewhere -> palette.paused
            HeadphoneStatus.None -> palette.neutral
        }
        StatusRow(
            dot = headphoneTone,
            label = stringResource(R.string.status_headphones),
            value = when (headphones) {
                is HeadphoneStatus.Connected ->
                    headphones.name ?: stringResource(R.string.status_headphones_generic)
                HeadphoneStatus.None -> stringResource(R.string.status_headphones_none)
                HeadphoneStatus.Disconnected -> stringResource(R.string.status_headphones_disconnected)
                HeadphoneStatus.BusyElsewhere -> stringResource(R.string.status_headphones_busy)
            },
            valueColor = if (headphones is HeadphoneStatus.Connected) {
                palette.onSurfaceVariant
            } else {
                headphoneTone
            },
        )

        if (errorDetail != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(palette.error),
                )
                Text(
                    text = stringResource(R.string.stopped_detail, errorDetail),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = palette.onErrorContainer,
                )
            }
        }

        // A report about a *past* session, not a state. Worded and placed so it cannot be read as
        // "this is happening now" - see docs/risks.md R1.
        if (endedUnexpectedly) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.ended_unexpectedly),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = palette.paused,
                )
                Text(
                    text = stringResource(R.string.action_dismiss),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismissNotice)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The dot repeats the value, it does not replace it - every row states its status in words. So the
 * dot is decorative and stays out of the accessibility tree.
 */
@Composable
private fun StatusRow(dot: Color, label: String, value: String, valueColor: Color) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(dot),
        )
        Text(label, fontSize = 15.sp, color = palette.statusLabel, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = valueColor)
    }
}

// ── Levels ───────────────────────────────────────────────────────────────────────────────────────

/**
 * Volume is shown, never set. Playback is `USAGE_MEDIA`, so it rides `STREAM_MUSIC` and the phone's
 * buttons - and the earbud's own controls, over A2DP absolute volume - already scale it. An in-app
 * slider could only attenuate, which is the wrong direction for the one complaint this product
 * reliably gets.
 */
@Composable
private fun VolumeReadout(volume: Float) {
    val palette = LocalPalette.current
    val filled = (volume * VOLUME_SEGMENTS).toInt().coerceIn(0, VOLUME_SEGMENTS)
    val percent = (volume * 100).toInt()
    val description = stringResource(R.string.volume_content_description, percent)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.label_volume), fontSize = 14.sp, color = palette.onSurfaceVariant)
            Text(stringResource(R.string.volume_hint), fontSize = 14.sp, color = palette.onSurfaceVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(VOLUME_SEGMENTS) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (index < filled) palette.neutral else palette.trackInactive),
                )
            }
        }
    }
}

/**
 * The only slider on the screen, so it can never be confused with volume.
 *
 * At the minimum it is genuinely off - both mechanisms disabled, not merely turned down. The caption
 * is deliberately not encouraging: more reduction thins the sound and can hide breathing, which is
 * the sound this product exists to carry.
 */
@Composable
private fun NoiseReduction(level: Float, live: Boolean, onChange: (Float) -> Unit) {
    val palette = LocalPalette.current
    val accent = if (live) palette.primary else palette.neutral

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.label_noise), fontSize = 14.sp, color = palette.onSurfaceVariant)
            Text(
                text = stringResource(noiseLevelLabel(level)),
                fontSize = 14.sp,
                color = palette.statusLabel,
            )
        }
        Slider(
            value = level,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = palette.trackInactive,
            ),
        )
        Text(
            text = stringResource(R.string.noise_caption),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = palette.footnote,
        )
    }
}

@StringRes
private fun noiseLevelLabel(level: Float): Int = when {
    level < 0.01f -> R.string.noise_off
    level < 0.34f -> R.string.noise_low
    level < 0.67f -> R.string.noise_medium
    else -> R.string.noise_high
}

// ── Diagnostics ──────────────────────────────────────────────────────────────────────────────────

/**
 * Not part of the design, which rightly has no settings screen.
 *
 * It exists because docs/test-matrix.md needs the `MIC`/`UNPROCESSED` A/B and the frame counters on
 * a real device, and neither can be driven from `adb`. **Hidden until the wordmark is long-pressed**
 * - on screen it took height the designed layout did not have spare, which pushed the state text
 * into a scrolling box while listening.
 */
@Composable
private fun Diagnostics(state: MonitorUiState, onSelectInput: (InputSource) -> Unit) {
    val palette = LocalPalette.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.diagnostics),
            fontSize = 12.sp,
            color = palette.footnote,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.inputSource == InputSource.Mic,
                onClick = { onSelectInput(InputSource.Mic) },
                label = { Text(stringResource(R.string.diagnostics_input_mic), fontSize = 12.sp) },
                enabled = !state.isActive,
            )
            FilterChip(
                selected = state.inputSource == InputSource.Unprocessed,
                onClick = { onSelectInput(InputSource.Unprocessed) },
                label = { Text(stringResource(R.string.diagnostics_input_unprocessed), fontSize = 12.sp) },
                enabled = !state.isActive && state.unprocessedSupported,
            )
        }
        if (!state.unprocessedSupported) {
            Text(
                text = stringResource(R.string.diagnostics_unprocessed_unsupported),
                fontSize = 12.sp,
                color = palette.footnote,
            )
        }
        if (state.routedIn != null && state.routedOut != null) {
            Text(
                text = stringResource(R.string.diagnostics_routing, state.routedIn, state.routedOut),
                fontSize = 12.sp,
                color = palette.footnote,
            )
        }
        state.counters?.let {
            Text(text = it, fontSize = 12.sp, lineHeight = 17.sp, color = palette.footnote)
        }
    }
}
