package com.yputria.remoteear.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Hosts monitoring for as long as the user wants it, surviving the app being backgrounded and the
 * screen locking. A foreground service of type `microphone` is the only mechanism Android provides
 * for that. See docs/adr/0005-foreground-service-hosts-monitoring.md.
 *
 * Two platform rules shape this class, both verified against current documentation in Phase 1:
 *
 *  1. `RECORD_AUDIO` must already be granted before the service is promoted, or API 34+ throws.
 *     The caller is responsible for that ordering - see [start].
 *  2. Because `RECORD_AUDIO` is a *while-in-use* permission, a microphone foreground service
 *     cannot be **started** from the background at all (SecurityException on API 34+, with a much
 *     shorter exemption list than the general ban). But the restriction does not apply to a service
 *     already running. That asymmetry is the whole reason this service **stays alive while paused**
 *     rather than stopping: a stopped service could never bring itself back once the phone is in
 *     another room.
 */
class MonitoringService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    /** Coroutines are right for the service's own bookkeeping - but never for the audio loop. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioManager: AudioManager
    private lateinit var pipeline: AudioPipeline

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        pipeline = AudioPipeline(audioManager, executor)
        MonitorNotification.ensureChannel(this)
        startBookkeeping()

        // Controls are collected rather than pushed, so the UI needs no binding and the settings
        // survive stop/start. Applied immediately, so a slider drag is not laggy.
        scope.launch { volume.collect { pipeline.setVolume(it) } }

        // One user-facing control drives both mechanisms. At zero everything is off; above zero the
        // filter scales continuously and the platform suppressor - which has no level of its own -
        // is simply on.
        scope.launch {
            noiseCancellation.collect { level ->
                pipeline.setNoiseReduction(level)
                pipeline.setNoiseSuppression(level > 0f)
            }
        }
    }

    /**
     * Publishes pipeline counters for the UI and watches for a loop that died on its own.
     *
     * Deliberately *not* logged here. Phase 2 found ColorOS chatty enough to rotate our counters
     * out of the logcat ring buffer within a minute, so the app is the source of truth for its own
     * numbers - which matters for the multi-hour Scenario G run.
     */
    private fun startBookkeeping() = scope.launch {
        while (true) {
            if (pipeline.isRunning) {
                _stats.value = pipeline.statsLine()
            }
            pipeline.loopError?.let { error ->
                Log.e(LOG_TAG, "loop died: $error")
                pipeline.stop()
                setState(MonitorState.Error(error))
            }
            delay(1_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> Log.w(LOG_TAG, "service started with no action; ignoring")
        }
        // Not sticky: monitoring is only ever started deliberately by a person in a visible app.
        // Letting the system resurrect it would both be creepy and fall foul of rule 2 above.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (_state.value is MonitorState.Monitoring) return

        val source = InputSource.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SOURCE) }
            ?: InputSource.Mic

        // Promote first, then open the streams. The notification must exist before we hold the
        // microphone, and startForeground has a few seconds' deadline.
        setState(MonitorState.Starting)
        try {
            ServiceCompat.startForeground(
                this,
                MonitorNotification.ID,
                MonitorNotification.build(this, MonitorState.Starting),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Most likely RECORD_AUDIO not granted, or a background start (rule 2).
            Log.e(LOG_TAG, "startForeground failed", e)
            setState(MonitorState.Error("Could not start monitoring: ${e.message}"))
            stopSelf()
            return
        }

        when (val outcome = pipeline.start(source)) {
            is StartOutcome.Started -> setState(
                MonitorState.Monitoring(
                    inputSource = source,
                    routedInType = pipeline.routedInType,
                    routedOutType = pipeline.routedOutType,
                ),
            )
            is StartOutcome.Failed -> {
                Log.e(LOG_TAG, "pipeline start failed: ${outcome.reason}")
                // No usable Bluetooth sink is a pause, not an error: it resolves by itself when the
                // headphones come back, and the service must be alive for that to work.
                if (outcome.reason.contains("Bluetooth", ignoreCase = true)) {
                    setState(MonitorState.Paused(PauseReason.BluetoothGone))
                } else {
                    setState(MonitorState.Error(outcome.reason))
                }
            }
        }
    }

    private fun handleStop() {
        pipeline.stop()
        setState(MonitorState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Single place that both publishes state and keeps the notification honest. */
    private fun setState(next: MonitorState) {
        _state.value = next
        if (next !is MonitorState.Idle) {
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(MonitorNotification.ID, MonitorNotification.build(this, next))
            }
        }
        Log.i(LOG_TAG, "state -> $next")
    }

    override fun onDestroy() {
        val dyingWhileActive = _state.value !is MonitorState.Idle
        pipeline.stop()
        scope.cancel()
        executor.shutdown()
        if (dyingWhileActive) {
            // The process is going away with monitoring apparently active - most likely an OEM
            // battery manager (docs/risks.md R1, ranked first). A session that ends without the
            // user stopping it is the failure this product most needs to be honest about, so
            // record it rather than letting it disappear silently.
            Log.w(LOG_TAG, "service destroyed while state=${_state.value} - unexpected stop")
            _endedUnexpectedly.value = true
        }
        _state.value = MonitorState.Idle
        _stats.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.yputria.remoteear.action.START"
        const val ACTION_STOP = "com.yputria.remoteear.action.STOP"
        const val EXTRA_SOURCE = "source"

        private val _state = MutableStateFlow<MonitorState>(MonitorState.Idle)
        private val _stats = MutableStateFlow<String?>(null)
        private val _endedUnexpectedly = MutableStateFlow(false)

        /**
         * Observed by the UI. Held in the companion so the UI needs no binding: the service is the
         * source of truth and the UI only ever watches. Phase 4 formalises this behind a ViewModel.
         */
        val state: StateFlow<MonitorState> = _state.asStateFlow()

        /** Live pipeline counters. Null when not monitoring. */
        val stats: StateFlow<String?> = _stats.asStateFlow()

        /**
         * True once a session has ended without the user stopping it - the R1 signature. The UI
         * surfaces this so an OEM kill becomes visible instead of the user simply never hearing
         * anything again.
         */
        val endedUnexpectedly: StateFlow<Boolean> = _endedUnexpectedly.asStateFlow()

        fun acknowledgeUnexpectedEnd() {
            _endedUnexpectedly.value = false
        }

        private val _volume = MutableStateFlow(1f)

        /** Per-track output gain, 0f..1f. Never the system stream volume. */
        val volume: StateFlow<Float> = _volume.asStateFlow()

        fun setVolume(value: Float) {
            _volume.value = value.coerceIn(0f, 1f)
        }

        private val _noiseCancellation = MutableStateFlow(0f)

        /**
         * The single noise-cancellation level, 0f (off) to 1f (strongest). **Defaults to off.**
         *
         * One control by design, rather than exposing the two mechanisms underneath it: our
         * adjustable high-pass filter (continuous) and the platform NoiseSuppressor (on/off only,
         * because Android provides no level for it). At zero both are inactive; above zero the
         * filter scales with the level and the suppressor is simply on.
         *
         * Worth remembering when picking defaults: the platform-suppressor half is the same
         * mechanism as docs/risks.md R2, so a high setting is the likeliest way to lose the quiet
         * ambient sounds this product exists to relay.
         */
        val noiseCancellation: StateFlow<Float> = _noiseCancellation.asStateFlow()

        fun setNoiseCancellation(level: Float) {
            _noiseCancellation.value = level.coerceIn(0f, 1f)
        }

        /**
         * **Must be called while an Activity is visible**, and only once `RECORD_AUDIO` is granted
         * - see rules 1 and 2 in the class documentation.
         */
        fun start(context: Context, source: InputSource) {
            val intent = Intent(context, MonitoringService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
