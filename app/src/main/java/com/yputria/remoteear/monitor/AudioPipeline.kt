package com.yputria.remoteear.monitor

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * The capture-to-playback pipeline. Owned by [MonitoringService] - nothing else may start it,
 * because microphone access depends on the service being in the foreground.
 *
 * Proven on hardware in Phase 2: the phone's built-in microphone was audible in a Bluetooth earbud
 * from another room, routed BUILTIN_MIC -> BLUETOOTH_A2DP with no SCO.
 * See docs/test-runs/2026-09-09-oneplus-cph2399.md.
 *
 * The invariants it enforces:
 *  - media path only, never the communication path (docs/adr/0004-media-path-only.md)
 *  - both ends pinned by device, and the *actual* routing asserted afterwards
 *  - 48 kHz mono PCM 16-bit, no resampling (docs/adr/0006-audio-format-and-buffering.md)
 *  - a dedicated thread at URGENT_AUDIO priority that allocates nothing per frame
 *  - silencing detected via isClientSilenced(), because losing the microphone produces
 *    silence rather than an error
 */
const val LOG_TAG = "RemoteEar"

private const val SAMPLE_RATE = 48_000
private const val FRAMES_PER_BUFFER = 960 // ~20 ms of mono audio
private const val FRAME_BYTES = FRAMES_PER_BUFFER * 2 // PCM 16-bit

/** A/B-tested on real hardware to answer hypothesis H4 / risk R2. */
enum class InputSource(val label: String, val source: Int) {
    Mic("MIC", MediaRecorder.AudioSource.MIC),
    Unprocessed("UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
}

sealed interface StartOutcome {
    data object Started : StartOutcome
    data class Failed(val reason: String) : StartOutcome
}

/** Human-readable [AudioDeviceInfo] type, so the evidence log is legible months later. */
fun deviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    26 -> "BLE_HEADSET" // AudioDeviceInfo.TYPE_BLE_HEADSET, API 31
    27 -> "BLE_SPEAKER"
    30 -> "BLE_BROADCAST"
    else -> "TYPE_$type"
}

/** Output device types RemoteEar will play to. [TYPE_BLUETOOTH_SCO] is deliberately excluded. */
fun AudioManager.usableBluetoothSinks(): List<AudioDeviceInfo> =
    getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

fun AudioManager.builtInMic(): AudioDeviceInfo? =
    getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

fun AudioManager.supportsUnprocessed(): Boolean =
    getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

class AudioPipeline(
    private val audioManager: AudioManager,
    private val executor: Executor,
) {
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile private var running = false

    // Counters are plain volatile primitives, read by the UI thread. This keeps the audio thread
    // allocation-free: it never formats a string or touches a collection.
    @Volatile var framesIn = 0L; private set
    @Volatile var framesOut = 0L; private set
    @Volatile var readNanos = 0L; private set
    @Volatile var writeNanos = 0L; private set
    @Volatile var blocks = 0L; private set
    @Volatile var clientSilenced = false; private set
    @Volatile var loopError: String? = null; private set
    @Volatile var routedInType = -1; private set
    @Volatile var routedOutType = -1; private set
    @Volatile var noiseSuppressionEnabled = false; private set
    @Volatile var noiseReduction = 0f; private set

    // High-pass filter coefficient, or -1 to bypass entirely. Written by the UI thread, read by the
    // audio thread. The two `hpPrev*` values are audio-thread-only state, so they need no volatile.
    @Volatile private var hpAlpha = -1f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    val isRunning: Boolean get() = running
    val underrunCount: Int get() = track?.underrunCount ?: 0

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == record?.audioSessionId }
                ?: return
            val silenced = mine.isClientSilenced
            if (silenced != clientSilenced) {
                clientSilenced = silenced
                // Losing the microphone yields silence, not an error: without this the app would
                // report "monitoring" while relaying zeros. See docs/risks.md R7.
                Log.w(LOG_TAG, "isClientSilenced -> $silenced")
            }
        }
    }

    fun start(inputSource: InputSource): StartOutcome {
        if (running) return StartOutcome.Failed("already running")

        val mic = audioManager.builtInMic()
            ?: return StartOutcome.Failed("no TYPE_BUILTIN_MIC reported by AudioManager")
        val sink = audioManager.usableBluetoothSinks().firstOrNull()
            ?: return StartOutcome.Failed("no Bluetooth A2DP or LE Audio output connected")

        val minRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val minTrack = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minRecord <= 0 || minTrack <= 0) {
            return StartOutcome.Failed("getMinBufferSize rejected 48 kHz mono (rec=$minRecord track=$minTrack)")
        }
        // Capture side: double the minimum. Measured 3840 -> 7680 bytes = 80 ms, cheap insurance
        // against scheduler pressure.
        val recordBytes = max(minRecord * 2, FRAME_BYTES * 4)

        // Output side: take the platform minimum as-is. Doubling it here was a mistake, borrowed
        // from built-in-speaker intuition where minimums are ~20 ms. Measured on the A2DP route the
        // platform minimum is already 20622 bytes = ~215 ms, so doubling added another ~215 ms of
        // latency for no glitch benefit. See docs/adr/0009-buffer-sizing-measured.md.
        val trackBytes = max(minTrack, FRAME_BYTES * 4)

        logEnvironment(mic, sink, minRecord, minTrack, recordBytes, trackBytes, inputSource)

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(inputSource.source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(recordBytes)
                .build()
        } catch (e: Exception) {
            return StartOutcome.Failed("AudioRecord.build failed: ${e.message}")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return StartOutcome.Failed("AudioRecord did not initialise")
        }

        // USAGE_MEDIA, never USAGE_VOICE_COMMUNICATION: the whole product depends on staying on
        // the media path so A2DP is not collapsed into narrowband SCO.
        val trk = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(trackBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            rec.release()
            return StartOutcome.Failed("AudioTrack.build failed: ${e.message}")
        }

        // A preferred device is a request, not a guarantee - hence the assertion below.
        rec.setPreferredDevice(mic)
        trk.setPreferredDevice(sink)

        // Must be registered before capture starts.
        rec.registerAudioRecordingCallback(executor, recordingCallback)

        attachNoiseSuppressor(rec)

        resetCounters()
        resetFilter()
        rec.startRecording()
        trk.play()

        val inType = rec.routedDevice?.type ?: -1
        val outType = trk.routedDevice?.type ?: -1
        routedInType = inType
        routedOutType = outType
        Log.i(
            LOG_TAG,
            "routing asserted: in=${deviceTypeName(inType)}($inType) " +
                "out=${deviceTypeName(outType)}($outType) rate=${rec.sampleRate}",
        )

        routingFailure(inType, outType)?.let { reason ->
            Log.e(LOG_TAG, "ROUTING FAILURE: $reason")
            rec.unregisterAudioRecordingCallback(recordingCallback)
            rec.stop(); rec.release()
            trk.stop(); trk.release()
            return StartOutcome.Failed(reason)
        }

        record = rec
        track = trk
        running = true
        thread = Thread({ runLoop(rec, trk) }, "remote-ear-monitor").apply { start() }
        Log.i(LOG_TAG, "monitor started (source=${inputSource.label})")
        return StartOutcome.Started
    }

    /**
     * TYPE_BLUETOOTH_SCO on either end is a hard error, never a degraded mode: on the input it
     * means the earbud's microphone is the source, which is the one thing this product forbids.
     */
    private fun routingFailure(inType: Int, outType: Int): String? = when {
        inType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            "input routed to BLUETOOTH_SCO - the earbud microphone is live, not the phone's"
        outType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            "output routed to BLUETOOTH_SCO - the link collapsed to narrowband hands-free"
        inType != AudioDeviceInfo.TYPE_BUILTIN_MIC ->
            "input routed to ${deviceTypeName(inType)}, expected BUILTIN_MIC"
        outType != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && outType != 26 ->
            "output routed to ${deviceTypeName(outType)}, expected BLUETOOTH_A2DP or BLE_HEADSET"
        else -> null
    }

    private fun runLoop(rec: AudioRecord, trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(FRAMES_PER_BUFFER) // allocated once, before the loop
        var inFrames = 0L
        var outFrames = 0L
        var readTotal = 0L
        var writeTotal = 0L
        var iterations = 0L

        while (running) {
            val t0 = System.nanoTime()
            val read = rec.read(buffer, 0, buffer.size)
            val t1 = System.nanoTime()
            if (read <= 0) {
                if (running) loopError = "AudioRecord.read returned $read"
                break
            }
            applyNoiseReduction(buffer, read)
            val written = trk.write(buffer, 0, read)
            val t2 = System.nanoTime()
            if (written < 0) {
                if (running) loopError = "AudioTrack.write returned $written"
                break
            }

            inFrames += read
            outFrames += written
            readTotal += t1 - t0
            writeTotal += t2 - t1
            iterations++

            // Publishing primitives only - no formatting, no allocation, no logging per frame.
            framesIn = inFrames
            framesOut = outFrames
            readNanos = readTotal
            writeNanos = writeTotal
            blocks = iterations
        }
        Log.i(LOG_TAG, "monitor loop exited (error=$loopError)")
    }

    fun stop() {
        if (!running && thread == null) return
        running = false
        // Stopping the record unblocks a thread parked in read().
        runCatching { record?.stop() }
        runCatching { track?.stop() }
        thread?.join(1_000)
        thread = null
        noiseSuppressor?.let { runCatching { it.release() } }
        noiseSuppressor = null
        record?.let {
            runCatching { it.unregisterAudioRecordingCallback(recordingCallback) }
            it.release()
        }
        track?.release()
        record = null
        track = null
        Log.i(LOG_TAG, "monitor stopped: framesIn=$framesIn framesOut=$framesOut")
    }

    // Deliberately no volume control here. The track is left at unity gain, and loudness belongs
    // to the phone's media volume - reachable from the phone's buttons and, via A2DP absolute
    // volume, from the earbud itself, which is the only control the user can reach once the phone
    // is in another room.
    //
    // An app-side AudioTrack.setVolume() would be worse than redundant: it caps at 1.0, so it can
    // only ever *attenuate*. It cannot make a quiet room easier to hear - the complaint this
    // product actually gets - while a forgotten setting would silently cap how loud the earbud can
    // get. Making the room louder needs gain above unity plus a limiter, applied in the loop
    // (mvp-scope.md S4), which is a different mechanism entirely.

    /**
     * Optional platform noise suppression on the capture session. **Default off, deliberately.**
     *
     * This is the same mechanism as [risk R2](../../../../../../docs/risks.md): suppressors are
     * tuned to isolate a near-field talker and discard ambient sound, but for a baby monitor the
     * ambient sound *is* the signal - breathing, rustling, a distant whimper. Enabling it may
     * suppress exactly what the user is listening for, so it is a user-visible experiment to A/B on
     * hardware, never a silent default.
     *
     * Note this effect is attached to the `MIC` capture session and is unrelated to
     * [AcousticEchoCanceler], which docs/adr/0008-sleep-sound-deferred.md addresses separately.
     */
    fun setNoiseSuppression(enabled: Boolean) {
        noiseSuppressionEnabled = enabled
        runCatching { noiseSuppressor?.enabled = enabled }
            .onFailure { Log.w(LOG_TAG, "could not toggle NoiseSuppressor: ${it.message}") }
        Log.i(LOG_TAG, "noise suppression -> $enabled")
    }

    /**
     * Continuously adjustable low-frequency noise reduction, 0f (off) to 1f (strongest).
     *
     * This exists because the platform's [NoiseSuppressor] has **no strength control** - it is
     * enabled or disabled, nothing in between - so anything adjustable has to be our own filter.
     *
     * It is a one-pole high-pass: it attenuates the low-frequency rumble people actually complain
     * about (fans, traffic, HVAC, handling noise) and leaves the mid and high band alone. That
     * choice is deliberate. **A high-pass cannot mute the room**, so unlike an adjustable noise
     * *gate* it does not re-create [risk R2] - it changes the tone of what you hear, never whether
     * you hear it. If a gate is ever wanted, it needs its own decision record, because silencing a
     * quiet room is the failure this product cannot afford.
     *
     * Cost: a few float operations per sample, no allocation, and fully bypassed at 0f.
     */
    fun setNoiseReduction(amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        noiseReduction = a
        hpAlpha = if (a < 0.01f) {
            -1f // bypass: leave the samples untouched
        } else {
            // 20 Hz (barely audible effect) up to 400 Hz (thin, voice-only)
            val cutoffHz = 20f + a * 380f
            val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
            val dt = 1f / SAMPLE_RATE
            rc / (rc + dt)
        }
        Log.i(LOG_TAG, "noise reduction -> $a (alpha=$hpAlpha)")
    }

    /** In-place one-pole high-pass. Called only from the audio thread; allocates nothing. */
    private fun applyNoiseReduction(buffer: ShortArray, frames: Int) {
        val alpha = hpAlpha
        if (alpha < 0f) return
        var prevIn = hpPrevIn
        var prevOut = hpPrevOut
        for (i in 0 until frames) {
            val x = buffer[i].toFloat()
            val y = alpha * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            buffer[i] = when {
                y > 32767f -> Short.MAX_VALUE
                y < -32768f -> Short.MIN_VALUE
                else -> y.toInt().toShort()
            }
        }
        hpPrevIn = prevIn
        hpPrevOut = prevOut
    }

    private fun attachNoiseSuppressor(rec: AudioRecord) {
        if (!isNoiseSuppressionAvailable()) {
            Log.i(LOG_TAG, "NoiseSuppressor unavailable on this device")
            return
        }
        noiseSuppressor = runCatching { NoiseSuppressor.create(rec.audioSessionId) }
            .onFailure { Log.w(LOG_TAG, "NoiseSuppressor.create failed: ${it.message}") }
            .getOrNull()
        runCatching { noiseSuppressor?.enabled = noiseSuppressionEnabled }
        Log.i(
            LOG_TAG,
            "NoiseSuppressor attached=${noiseSuppressor != null} enabled=$noiseSuppressionEnabled",
        )
    }

    fun statsLine(): String {
        val n = blocks
        val avgRead = if (n > 0) readNanos / n / 1000 else 0
        val avgWrite = if (n > 0) writeNanos / n / 1000 else 0
        val drift = framesIn - framesOut
        return "frames in=$framesIn out=$framesOut drift=$drift underruns=$underrunCount " +
            "avgRead=${avgRead}us avgWrite=${avgWrite}us silenced=$clientSilenced"
    }

    /** Filter memory must not carry across sessions, or the first frames click. */
    private fun resetFilter() {
        hpPrevIn = 0f
        hpPrevOut = 0f
    }

    private fun resetCounters() {
        framesIn = 0; framesOut = 0; readNanos = 0; writeNanos = 0; blocks = 0
        clientSilenced = false; loopError = null
    }

    private fun logEnvironment(
        mic: AudioDeviceInfo,
        sink: AudioDeviceInfo,
        minRecord: Int,
        minTrack: Int,
        recordBytes: Int,
        trackBytes: Int,
        inputSource: InputSource,
    ) {
        Log.i(LOG_TAG, "--- RemoteEar Phase 2 prototype: environment ---")
        Log.i(LOG_TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
        Log.i(LOG_TAG, "UNPROCESSED supported=${audioManager.supportsUnprocessed()} using=${inputSource.label}")
        Log.i(LOG_TAG, "output sample rate property=${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}")
        Log.i(LOG_TAG, "output frames per buffer=${audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        Log.i(LOG_TAG, "chosen input  = ${deviceTypeName(mic.type)} id=${mic.id}")
        Log.i(LOG_TAG, "chosen output = ${deviceTypeName(sink.type)} id=${sink.id}")
        Log.i(LOG_TAG, "buffers: minRecord=$minRecord -> $recordBytes, minTrack=$minTrack -> $trackBytes")
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            Log.i(LOG_TAG, "  out device: ${deviceTypeName(it.type)}(${it.type}) id=${it.id}")
        }
    }
}

/** Whether the platform offers noise suppression at all. Device-dependent. */
fun isNoiseSuppressionAvailable(): Boolean = runCatching { NoiseSuppressor.isAvailable() }
    .getOrDefault(false)

/**
 * The phone's media volume as a 0f..1f fraction.
 *
 * Read-only by design. Because playback uses `USAGE_MEDIA` it rides `STREAM_MUSIC`, so the physical
 * volume buttons and the earbud's own controls already scale what the listener hears. Android
 * advises against `setStreamVolume`/`adjustStreamVolume` because they change volume for every app,
 * and docs/adr/0007-minimal-permission-set.md keeps this app out of global audio state.
 */
fun AudioManager.streamMusicFraction(): Float {
    val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= 0) return 0f
    return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}
