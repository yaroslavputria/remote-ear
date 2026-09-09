package com.yputria.remoteear.proto

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * PHASE 2 PROTOTYPE - deliberately disposable.
 *
 * Its only job is to answer the project's go/no-go question: can the phone's built-in microphone
 * be heard through Bluetooth headphones from another room? Phase 3 moves this pipeline into a
 * foreground service and Phase 4 replaces the UI; this class is expected to be rewritten.
 *
 * What is NOT disposable is the set of invariants it demonstrates:
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

class MonitorLoop(
    private val audioManager: AudioManager,
    private val executor: Executor,
) {
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

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
        val recordBytes = max(minRecord * 2, FRAME_BYTES * 4)
        val trackBytes = max(minTrack * 2, FRAME_BYTES * 4)

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

        resetCounters()
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
        record?.let {
            runCatching { it.unregisterAudioRecordingCallback(recordingCallback) }
            it.release()
        }
        track?.release()
        record = null
        track = null
        Log.i(LOG_TAG, "monitor stopped: framesIn=$framesIn framesOut=$framesOut")
    }

    /** 0f..1f per-track gain. Never touches system stream volume. */
    fun setVolume(volume: Float) {
        track?.setVolume(volume.coerceIn(0f, 1f))
    }

    fun statsLine(): String {
        val n = blocks
        val avgRead = if (n > 0) readNanos / n / 1000 else 0
        val avgWrite = if (n > 0) writeNanos / n / 1000 else 0
        val drift = framesIn - framesOut
        return "frames in=$framesIn out=$framesOut drift=$drift underruns=$underrunCount " +
            "avgRead=${avgRead}us avgWrite=${avgWrite}us silenced=$clientSilenced"
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
