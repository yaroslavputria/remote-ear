package com.yputria.remoteear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.monitor.MonitorNotification
import com.yputria.remoteear.monitor.MonitorState
import com.yputria.remoteear.monitor.MonitoringService
import com.yputria.remoteear.monitor.deviceTypeName
import com.yputria.remoteear.monitor.isNoiseSuppressionAvailable
import com.yputria.remoteear.monitor.supportsUnprocessed
import com.yputria.remoteear.monitor.usableBluetoothSinks
import com.yputria.remoteear.theme.RemoteEarTheme

/**
 * PHASE 3 SCREEN - still deliberately plain. Phase 4 replaces it with the designed UI from
 * docs/design-brief.md, driven by a ViewModel.
 *
 * What changed from Phase 2: this no longer owns the audio pipeline. It starts and stops
 * [MonitoringService] and *observes* its state, because microphone access in the background
 * requires the service to be the owner. Note there is no longer any FLAG_KEEP_SCREEN_ON - the
 * service is what keeps monitoring alive now, and needing the screen on would defeat the point.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteEarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MonitorScreen()
                }
            }
        }
    }
}

@Composable
private fun MonitorScreen() {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val state by MonitoringService.state.collectAsState()
    val stats by MonitoringService.stats.collectAsState()
    val endedUnexpectedly by MonitoringService.endedUnexpectedly.collectAsState()
    val volume by MonitoringService.volume.collectAsState()
    val noiseSuppression by MonitoringService.noiseSuppression.collectAsState()
    val noiseReduction by MonitoringService.noiseReduction.collectAsState()

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsRequested by remember { mutableStateOf(false) }
    var inputSource by remember { mutableStateOf(InputSource.Mic) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMic = granted }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsRequested = true }

    val unprocessedSupported = remember { audioManager.supportsUnprocessed() }
    val noiseSuppressionAvailable = remember { isNoiseSuppressionAvailable() }

    // Live Bluetooth presence. Recomputing this only on recomposition would leave the Listen
    // button stale when headphones connect or disconnect - so it is driven by the audio system's
    // own callback, which also needs no Bluetooth permission.
    var sinks by remember { mutableStateOf(audioManager.usableBluetoothSinks()) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                sinks = audioManager.usableBluetoothSinks()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                sinks = audioManager.usableBluetoothSinks()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    val isActive = state is MonitorState.Monitoring || state is MonitorState.Starting
    val canListen = hasMic && sinks.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RemoteEar", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Phase 3 - monitoring runs in a foreground service",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        // State first: this is the safety-relevant information.
        Text(MonitorNotification.title(state), style = MaterialTheme.typography.titleMedium)
        Text(MonitorNotification.detail(state), style = MaterialTheme.typography.bodyMedium)

        if (endedUnexpectedly) {
            Text(
                "Monitoring stopped on its own last time. Some phones shut down background apps " +
                    "to save battery — allowing RemoteEar to run in the background in your system " +
                    "settings may prevent it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { MonitoringService.acknowledgeUnexpectedEnd() }) { Text("Dismiss") }
        }

        Spacer(Modifier.height(4.dp))

        Text("Microphone permission: ${if (hasMic) "granted" else "NOT granted"}")
        if (!hasMic) {
            Text(
                "RemoteEar needs the microphone so you can hear this room through your headphones. " +
                    "Audio is never recorded or sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone access")
            }
        }

        Text(
            "Bluetooth output: " +
                if (sinks.isEmpty()) "none connected"
                else sinks.joinToString { deviceTypeName(it.type) },
        )
        Text("UNPROCESSED supported: $unprocessedSupported")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = inputSource == InputSource.Mic,
                onClick = { inputSource = InputSource.Mic },
                label = { Text("MIC") },
                enabled = !isActive,
            )
            FilterChip(
                selected = inputSource == InputSource.Unprocessed,
                onClick = { inputSource = InputSource.Unprocessed },
                label = { Text("UNPROCESSED") },
                enabled = !isActive && unprocessedSupported,
            )
        }

        Button(
            enabled = isActive || canListen,
            onClick = {
                if (isActive) {
                    MonitoringService.stop(context)
                } else {
                    // Ask for notification permission before starting: without it the ongoing
                    // notification is hidden, which is poor for an app holding the microphone.
                    // It is a soft dependency though - denial does not block the service.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !notificationsRequested &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    // Started from a visible Activity, with RECORD_AUDIO already granted - both
                    // are hard platform requirements for a microphone foreground service.
                    MonitoringService.start(context, inputSource)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isActive) "Stop listening" else "Listen")
        }

        // A disabled button should say why rather than just sitting there inert.
        if (!isActive && !canListen) {
            Text(
                when {
                    !hasMic -> "Grant microphone access to start listening."
                    else -> "Connect your Bluetooth headphones to start listening."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        (state as? MonitorState.Monitoring)?.let {
            Text(
                "Routing: in=${deviceTypeName(it.routedInType)} out=${deviceTypeName(it.routedOutType)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text("Output volume: ${(volume * 100).toInt()}%")
        Slider(
            value = volume,
            onValueChange = { MonitoringService.setVolume(it) },
        )

        Text(
            "Noise reduction: " +
                if (noiseReduction < 0.01f) "off" else "${(noiseReduction * 100).toInt()}%",
        )
        Slider(
            value = noiseReduction,
            onValueChange = { MonitoringService.setNoiseReduction(it) },
        )
        Text(
            "Reduces low-frequency rumble — fans, traffic, air conditioning. Turning it up makes " +
                "the sound thinner but never quieter, so it cannot mute the room.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (noiseSuppressionAvailable) {
                    "Device noise suppression"
                } else {
                    "Device noise suppression (unavailable)"
                },
            )
            Switch(
                checked = noiseSuppression,
                onCheckedChange = { MonitoringService.setNoiseSuppression(it) },
                enabled = noiseSuppressionAvailable,
            )
        }
        Text(
            "On/off only — Android offers no strength control for this one. It is tuned to isolate " +
                "a nearby voice and discard background sound, but here the background is what you " +
                "want to hear, so it can suppress the very thing you are listening for. Off by " +
                "default; try it both ways in a quiet room.",
            style = MaterialTheme.typography.bodySmall,
        )

        stats?.let {
            Text("Counters", style = MaterialTheme.typography.titleSmall)
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Audio is processed on this phone only and played to your headphones. Nothing is " +
                "recorded, saved, or sent anywhere. RemoteEar has no internet access.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
