package com.yputria.remoteear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yputria.remoteear.proto.InputSource
import com.yputria.remoteear.proto.LOG_TAG
import com.yputria.remoteear.proto.MonitorLoop
import com.yputria.remoteear.proto.StartOutcome
import com.yputria.remoteear.proto.deviceTypeName
import com.yputria.remoteear.proto.supportsUnprocessed
import com.yputria.remoteear.proto.usableBluetoothSinks
import com.yputria.remoteear.theme.RemoteEarTheme
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/**
 * PHASE 2 PROTOTYPE - deliberately disposable. Phase 4 replaces this with a real Compose UI
 * driven by a ViewModel over a StateFlow; this screen exists only to drive [MonitorLoop] and
 * show the evidence needed for the go/no-go gate.
 */
class MainActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var audioManager: AudioManager
    private lateinit var monitor: MonitorLoop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Test integrity, not a feature: there is no foreground service until Phase 3, so a
        // screen timeout would silently kill capture and read as "the audio path failed".
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        monitor = MonitorLoop(audioManager, executor)

        setContent {
            RemoteEarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProtoScreen(audioManager, monitor)
                }
            }
        }
    }

    override fun onDestroy() {
        monitor.stop()
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun ProtoScreen(audioManager: AudioManager, monitor: MonitorLoop) {
    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMicPermission = granted }

    val unprocessedSupported = remember { audioManager.supportsUnprocessed() }
    var inputSource by remember { mutableStateOf(InputSource.Mic) }
    var running by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }
    var status by remember { mutableStateOf("idle") }
    var stats by remember { mutableStateOf("") }
    var routing by remember { mutableStateOf("") }

    // Bluetooth sinks are read on each recomposition; a proper AudioDeviceCallback arrives in
    // Phase 4 along with the real status UI.
    val sinks = audioManager.usableBluetoothSinks()

    LaunchedEffect(running) {
        var tick = 0
        while (running) {
            stats = monitor.statsLine()
            // Every ~10 s, never per frame.
            if (tick % 10 == 0) Log.i(LOG_TAG, "stats: $stats")
            monitor.loopError?.let {
                status = "loop failed: $it"
                monitor.stop()
                running = false
            }
            tick++
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RemoteEar", style = MaterialTheme.typography.headlineMedium)
        Text("Phase 2 prototype - proves mic -> Bluetooth only", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(4.dp))

        Text("Microphone permission: ${if (hasMicPermission) "granted" else "NOT granted"}")
        if (!hasMicPermission) {
            Text(
                "RemoteEar needs the microphone so you can hear this room through your headphones. " +
                    "Audio is never recorded or sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant microphone access")
            }
        }

        Text("Bluetooth output: ${if (sinks.isEmpty()) "none connected" else sinks.joinToString { deviceTypeName(it.type) }}")
        Text("UNPROCESSED supported: $unprocessedSupported")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = inputSource == InputSource.Mic,
                onClick = { if (!running) inputSource = InputSource.Mic },
                label = { Text("MIC") },
                enabled = !running,
            )
            FilterChip(
                selected = inputSource == InputSource.Unprocessed,
                onClick = { if (!running) inputSource = InputSource.Unprocessed },
                label = { Text("UNPROCESSED") },
                enabled = !running && unprocessedSupported,
            )
        }

        Button(
            enabled = hasMicPermission && (running || sinks.isNotEmpty()),
            onClick = {
                if (running) {
                    monitor.stop()
                    running = false
                    status = "stopped"
                    routing = ""
                } else {
                    when (val outcome = monitor.start(inputSource)) {
                        is StartOutcome.Started -> {
                            monitor.setVolume(volume)
                            running = true
                            status = "monitoring (${inputSource.label})"
                            routing = "in=${deviceTypeName(monitor.routedInType)} " +
                                "out=${deviceTypeName(monitor.routedOutType)}"
                        }
                        is StartOutcome.Failed -> {
                            status = "FAILED: ${outcome.reason}"
                            routing = ""
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "STOP" else "START")
        }

        Text("Status: $status")
        if (routing.isNotEmpty()) Text("Routing: $routing")

        Text("Output volume: ${(volume * 100).toInt()}%")
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                monitor.setVolume(it)
            },
        )

        if (stats.isNotEmpty()) {
            Text("Counters", style = MaterialTheme.typography.titleSmall)
            Text(stats, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Audio is processed on this phone only and played to your headphones. Nothing is " +
                "recorded, saved, or sent anywhere. RemoteEar has no internet access.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
