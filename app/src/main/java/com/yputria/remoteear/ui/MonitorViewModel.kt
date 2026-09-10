package com.yputria.remoteear.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yputria.remoteear.monitor.InputSource
import com.yputria.remoteear.monitor.MonitorState
import com.yputria.remoteear.monitor.MonitoringService
import com.yputria.remoteear.monitor.PauseReason
import com.yputria.remoteear.monitor.SessionMarker
import com.yputria.remoteear.monitor.deviceTypeName
import com.yputria.remoteear.monitor.headphoneName
import com.yputria.remoteear.monitor.streamMusicFraction
import com.yputria.remoteear.monitor.supportsUnprocessed
import com.yputria.remoteear.monitor.usableBluetoothSinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Turns everything the screen needs into one [MonitorUiState].
 *
 * It owns no audio. The pipeline lives in [MonitoringService] because microphone access in the
 * background depends on the service being the owner (ADR-0005), so this class only *observes* it and
 * merges in the three things the service does not know about: whether RECORD_AUDIO is granted, which
 * Bluetooth sinks exist, and where the phone's media volume currently sits.
 *
 * All three need live callbacks rather than a read at composition time. The Listen button enables
 * itself when headphones connect, which cannot work from a value sampled once - a bug this project
 * has already had.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val marker = SessionMarker(app)

    private val micGranted = MutableStateFlow(hasMicPermission())
    private val micPermanentlyDenied = MutableStateFlow(false)

    /**
     * Whether the last session ended without anyone stopping it - read from durable storage, not
     * from the service, because the process that would have reported it may be the thing that died.
     */
    private val endedUnexpectedly = MutableStateFlow(false)
    private val sinks = MutableStateFlow(currentSinks())
    private val volume = MutableStateFlow(audioManager.streamMusicFraction())
    private val inputSource = MutableStateFlow(InputSource.Mic)

    /** Fixed for the life of the process: it is a hardware property, not a state. */
    val unprocessedSupported: Boolean = audioManager.supportsUnprocessed()

    // Bluetooth presence, live. This needs no Bluetooth permission - getDevices() exposes device
    // types, which is all we need. See ADR-0007.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            sinks.value = currentSinks()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            sinks.value = currentSinks()
        }
    }

    // The phone's media volume. Mirrored, never written: playback is USAGE_MEDIA and so rides
    // STREAM_MUSIC, which the physical buttons and the earbud's own controls already scale.
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            volume.value = audioManager.streamMusicFraction()
        }
    }

    init {
        reconcileLastSession()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        getApplication<Application>().contentResolver.unregisterContentObserver(volumeObserver)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // The headphones name, when the platform offers one. Kept as a list so "is anything connected"
    // and "what is it called" come from the same read.
    private fun currentSinks(): List<Sink> =
        audioManager.usableBluetoothSinks().map { Sink(it.headphoneName(), deviceTypeName(it.type)) }

    /**
     * A session is only "still running" if the service says so *now*. Asked on every resume,
     * because the interesting case is the user opening the app hours later to find out what
     * happened.
     */
    private fun reconcileLastSession() {
        endedUnexpectedly.value =
            marker.reconcile(serviceRunning = MonitoringService.state.value !is MonitorState.Idle)
    }

    /** Permission and volume can change while the Activity is stopped, so re-read them on resume. */
    fun refresh() {
        reconcileLastSession()
        micGranted.value = hasMicPermission()
        if (micGranted.value) micPermanentlyDenied.value = false
        sinks.value = currentSinks()
        volume.value = audioManager.streamMusicFraction()
    }

    /**
     * [canAskAgain] is `shouldShowRequestPermissionRationale` read *after* the dialog closed. At that
     * point false means Android has stopped asking, which is the only reliable way to detect a
     * permanent denial without persisting a "have we asked yet" flag.
     */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        micGranted.value = granted
        micPermanentlyDenied.value = !granted && !canAskAgain
    }

    fun selectInputSource(source: InputSource) {
        inputSource.value = source
    }

    fun setNoiseReduction(level: Float) = MonitoringService.setNoiseCancellation(level)

    fun dismissUnexpectedEnd() {
        marker.acknowledge()
        endedUnexpectedly.value = false
    }

    fun listen() = MonitoringService.start(getApplication(), inputSource.value)

    fun stop() = MonitoringService.stop(getApplication())

    private data class Service(
        val state: MonitorState,
        val counters: String?,
        val endedUnexpectedly: Boolean,
        val noise: Float,
    )

    /** [name] is null when the platform will not give one; [type] is for diagnostics only. */
    data class Sink(val name: String?, val type: String)

    private data class Local(
        val micGranted: Boolean,
        val micPermanentlyDenied: Boolean,
        val sinks: List<Sink>,
        val volume: Float,
        val inputSource: InputSource,
    )

    val uiState: StateFlow<MonitorUiState> = combine(
        combine(
            MonitoringService.state,
            MonitoringService.stats,
            endedUnexpectedly,
            MonitoringService.noiseCancellation,
            ::Service,
        ),
        combine(micGranted, micPermanentlyDenied, sinks, volume, inputSource, ::Local),
        ::build,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = build(
            Service(MonitorState.Idle, null, false, 0f),
            Local(hasMicPermission(), false, currentSinks(), volume.value, InputSource.Mic),
        ),
    )

    private fun build(service: Service, local: Local): MonitorUiState {
        val state = service.state
        val pauseReason = (state as? MonitorState.Paused)?.reason

        // Order is the point. Permission comes first because nothing else can work without it, and
        // an active pipeline outranks "no headphones connected" - if audio is flowing, saying
        // otherwise would be a lie regardless of what getDevices() reports this instant.
        val screen = when {
            !local.micGranted -> Screen.PermissionMissing
            state is MonitorState.Starting -> Screen.Starting
            state is MonitorState.Monitoring -> Screen.Listening
            state is MonitorState.Paused -> Screen.Paused(state.reason)
            state is MonitorState.Error -> Screen.Stopped(state.kind, state.message)
            local.sinks.isEmpty() -> Screen.NoHeadphones
            else -> Screen.Idle
        }

        val micStatus = when {
            !local.micGranted -> MicStatus.Denied
            state is MonitorState.Monitoring -> MicStatus.InUseByUs
            pauseReason == PauseReason.Call -> MicStatus.UsedByCall
            pauseReason == PauseReason.MicPreempted -> MicStatus.UsedByOtherApp
            else -> MicStatus.Allowed
        }

        val headphones = when {
            pauseReason == PauseReason.BluetoothGone -> HeadphoneStatus.Disconnected
            pauseReason == PauseReason.AudioFocusLost -> HeadphoneStatus.BusyElsewhere
            local.sinks.isEmpty() -> HeadphoneStatus.None
            else -> HeadphoneStatus.Connected(local.sinks.first().name)
        }

        val monitoring = state as? MonitorState.Monitoring

        return MonitorUiState(
            screen = screen,
            micStatus = micStatus,
            headphones = headphones,
            volume = local.volume,
            noiseReduction = service.noise,
            endedUnexpectedly = service.endedUnexpectedly,
            micPermanentlyDenied = local.micPermanentlyDenied,
            inputSource = local.inputSource,
            unprocessedSupported = unprocessedSupported,
            routedIn = monitoring?.let { deviceTypeName(it.routedInType) },
            routedOut = monitoring?.let { deviceTypeName(it.routedOutType) },
            counters = service.counters,
        )
    }
}
