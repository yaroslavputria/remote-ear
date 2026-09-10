package com.yputria.remoteear.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * Phase 5 added the interruption handling, which is the part that decides whether this is a demo or
 * something you would leave running near a child. Four things can take the room away, and each one
 * has to be **noticed, named, and recovered from**:
 *
 * | What happens | How we find out | What the user sees | Recovery |
 * |---|---|---|---|
 * | A call starts | audio focus loss, with the audio mode reading as a call | `Paused · A phone call is in progress` | when the call ends |
 * | Another app plays audio | audio focus loss | `Paused · Another app is playing sound` | when that app stops |
 * | Headphones disconnect | `AudioDeviceCallback` | `Paused · Bluetooth headphones disconnected` | on reconnect |
 * | Another app takes the microphone | `isClientSilenced()` | `Paused · Another app is using the microphone` | when it releases it |
 *
 * The last one is the dangerous one and the reason [AudioPipeline] registers a recording callback at
 * all: Android hands a losing capture client **buffers of zeros rather than an error**, so without
 * it the app would relay digital silence while displaying "Listening". In a monitor, silence is
 * indistinguishable from a quiet room. See docs/risks.md R7.
 */
class MonitoringService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    /** Coroutines are right for the service's own bookkeeping - but never for the audio loop. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Every state transition goes through this.
     *
     * Transitions now arrive from three places at once - the audio focus listener and the device
     * callback on the main thread, the watcher on a background dispatcher - and they all
     * start and stop the pipeline. Two of them interleaving would mean opening streams that another
     * has just closed.
     */
    private val transitions = Mutex()

    private lateinit var audioManager: AudioManager
    private lateinit var pipeline: AudioPipeline
    private lateinit var focus: AudioFocusOwner

    /** Remembered so an automatic resume uses the source the user actually chose. */
    private var source = InputSource.Mic

    /** The last live routing, so recovering from a mic preemption need not restart the streams. */
    private var lastMonitoring: MonitorState.Monitoring? = null

    /** Ticks since the last resume attempt, to avoid retrying a failing start every second. */
    private var sinceResumeAttempt = 0

    /**
     * Headphones appearing and disappearing.
     *
     * This is the primary signal for a disconnect: it fires immediately, whereas the watcher below
     * would take up to a second to notice. Both exist because a monitor that keeps saying
     * "Listening" into a disconnected earbud is the failure this product cannot have.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            if (_state.value == MonitorState.Paused(PauseReason.BluetoothGone)) tryResume()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            if (audioManager.usableBluetoothSinks().isEmpty()) {
                pause(PauseReason.BluetoothGone)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        pipeline = AudioPipeline(audioManager, executor)
        focus = AudioFocusOwner(
            audioManager = audioManager,
            onLoss = { onFocusLost() },
            onGain = { tryResume() },
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        MonitorNotification.ensureChannel(this)
        startWatching()

        // Controls are collected rather than pushed, so the UI needs no binding and the settings
        // survive stop/start. Applied immediately, so a slider drag is not laggy.
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
     * Publishes counters, notices a loop that died on its own, and drives recovery.
     *
     * Counters are deliberately *not* logged here. Phase 2 found ColorOS chatty enough to rotate
     * ours out of the logcat ring buffer within a minute, so the app is the source of truth for its
     * own numbers - which matters for the multi-hour Scenario G run.
     */
    private fun startWatching() = scope.launch {
        while (true) {
            transitions.withLock { tick() }
            delay(TICK_MS)
        }
    }

    private fun tick() {
        if (pipeline.isRunning) _stats.value = pipeline.statsLine()

        pipeline.loopError?.let { error ->
            Log.e(LOG_TAG, "loop died: $error")
            pipeline.stop()
            focus.abandon()
            setState(MonitorState.Error(error))
            return
        }

        sinceResumeAttempt++

        when (val state = _state.value) {
            is MonitorState.Monitoring -> {
                // Losing the microphone produces silence, not an error, so this flag is the only
                // thing standing between the user and a monitor that has quietly stopped working.
                if (pipeline.clientSilenced) pauseLocked(PauseReason.MicPreempted)
            }

            is MonitorState.Paused -> when (state.reason) {
                // The streams are still open in this state, which is what makes the recovery
                // immediate: the same callback that reported the silencing reports its end.
                PauseReason.MicPreempted ->
                    if (!pipeline.clientSilenced) restoreMonitoring()

                // "Listening continues by itself when the call ends" - so something has to be
                // watching for the end of the call. There is no callback for it that does not cost
                // a permission, so it is polled.
                PauseReason.Call -> if (!inCall()) attemptResumeLocked()

                // isMusicActive() is coarse, and it is what makes "stop that app and listening
                // continues by itself" true rather than aspirational: after a permanent focus
                // loss Android does not send us a GAIN, so waiting for one would wait forever.
                PauseReason.AudioFocusLost ->
                    if (!inCall() && !audioManager.isMusicActive) attemptResumeLocked()

                PauseReason.BluetoothGone ->
                    if (audioManager.usableBluetoothSinks().isNotEmpty()) attemptResumeLocked()
            }

            else -> Unit
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

        source = InputSource.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SOURCE) }
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

        startPipeline()
    }

    /** Requests focus, opens the streams, and publishes whatever actually happened. */
    private fun startPipeline() {
        focus.request()
        when (val outcome = pipeline.start(source)) {
            is StartOutcome.Started -> {
                val monitoring = MonitorState.Monitoring(
                    inputSource = source,
                    routedInType = pipeline.routedInType,
                    routedOutType = pipeline.routedOutType,
                )
                lastMonitoring = monitoring
                setState(monitoring)
            }
            is StartOutcome.Failed -> {
                Log.e(LOG_TAG, "pipeline start failed: ${outcome.reason}")
                setState(
                    when (outcome.cause) {
                        // No usable sink is a pause, not an error: it resolves by itself when the
                        // headphones come back, and the service must be alive for that to work.
                        FailureCause.NoBluetoothSink ->
                            MonitorState.Paused(PauseReason.BluetoothGone)
                        // A wrong route is never a pause. It is invariant 2 firing, and it gets its
                        // own message because the user's headset microphone would have been live.
                        FailureCause.WrongRoute ->
                            MonitorState.Error(outcome.reason, ErrorKind.WrongRoute)
                        FailureCause.AudioOpen ->
                            MonitorState.Error(outcome.reason, ErrorKind.AudioOpenFailed)
                    },
                )
                if (outcome.cause != FailureCause.NoBluetoothSink) focus.abandon()
            }
        }
    }

    /**
     * A call and another app's playback both arrive as a focus loss; only the audio mode tells them
     * apart, and they need different words.
     *
     * The transient and permanent losses are handled identically. The difference between them is
     * about *who resumes*, and since both are recovered by the watcher rather than by waiting for a
     * GAIN, it makes no difference here.
     */
    private fun onFocusLost() {
        pause(if (inCall()) PauseReason.Call else PauseReason.AudioFocusLost)
    }

    /**
     * True during a telephony or VoIP call.
     *
     * This **reads** the audio mode. ADR-0004 forbids *setting* it - `setMode` hands the device to
     * the communication path and the earbud's microphone becomes the input, which is the failure
     * this product exists to avoid. Reading it costs nothing and needs no permission, and it is the
     * only way to name a call without `READ_PHONE_STATE`.
     */
    private fun inCall(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun pause(reason: PauseReason) = scope.launch {
        transitions.withLock { pauseLocked(reason) }
    }

    /**
     * Stops relaying and says why.
     *
     * [PauseReason.MicPreempted] is the exception that keeps its streams open: the recording
     * callback on our own live `AudioRecord` is the only thing that can tell us the other app has
     * finished, so releasing it would leave us guessing with a backoff timer. The user-facing claim
     * is honest either way - they are not hearing the room - and this way recovery is immediate.
     *
     * Every other reason releases the microphone and the focus. During a call that is not merely
     * tidy: holding the microphone through someone's phone call would be indefensible.
     */
    private fun pauseLocked(reason: PauseReason) {
        val current = _state.value
        if (current is MonitorState.Paused && current.reason == reason) return
        if (current is MonitorState.Idle || current is MonitorState.Error) return

        // A call outranks a mic preemption: same silence, but the honest explanation differs.
        if (current is MonitorState.Paused &&
            current.reason != PauseReason.MicPreempted &&
            reason != PauseReason.Call
        ) {
            return
        }

        (current as? MonitorState.Monitoring)?.let { lastMonitoring = it }

        if (reason != PauseReason.MicPreempted) {
            pipeline.stop()
            focus.abandon()
        }
        sinceResumeAttempt = 0
        setState(MonitorState.Paused(reason))
    }

    /** Recovery from a silencing: nothing was torn down, so nothing needs rebuilding. */
    private fun restoreMonitoring() {
        val monitoring = lastMonitoring ?: return
        Log.i(LOG_TAG, "microphone released by the other app - resuming")
        setState(monitoring)
    }

    private fun tryResume() = scope.launch {
        transitions.withLock { attemptResumeLocked() }
    }

    /**
     * Reopens the streams, at most every [RESUME_BACKOFF_TICKS] ticks.
     *
     * The backoff matters because the conditions that trigger a resume attempt can be true while
     * the attempt still fails - an A2DP sink is reported as present a moment before it will accept
     * a stream - and retrying every second would fill the log and thrash the audio server.
     */
    private fun attemptResumeLocked() {
        if (_state.value !is MonitorState.Paused) return
        if (sinceResumeAttempt < RESUME_BACKOFF_TICKS) return
        sinceResumeAttempt = 0
        Log.i(LOG_TAG, "attempting to resume from ${_state.value}")
        setState(MonitorState.Starting)
        startPipeline()
    }

    private fun handleStop() {
        pipeline.stop()
        focus.abandon()
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
        focus.abandon()
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
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

        private const val TICK_MS = 1_000L

        /** Two seconds between resume attempts. See [attemptResumeLocked]. */
        private const val RESUME_BACKOFF_TICKS = 2

        private val _state = MutableStateFlow<MonitorState>(MonitorState.Idle)
        private val _stats = MutableStateFlow<String?>(null)
        private val _endedUnexpectedly = MutableStateFlow(false)

        /**
         * Observed by the UI. Held in the companion so the UI needs no binding: the service is the
         * source of truth and the UI only ever watches, behind a ViewModel since Phase 4.
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
