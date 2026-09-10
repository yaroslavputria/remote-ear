package com.yputria.remoteear.monitor

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Audio focus, requested on the **media** path like everything else here.
 *
 * Focus matters for two different reasons, and only the second is about being a good citizen:
 *
 *  1. **It is how we find out we have been interrupted.** A call or another app starting playback
 *     takes focus, and that callback is the earliest, most reliable signal that the user has
 *     stopped hearing the room. Without it the app would keep writing frames into an output the
 *     platform has taken away, and go on claiming to be listening.
 *  2. Whatever the user was playing stops when monitoring starts. That is correct: one earbud
 *     cannot deliver a room and a podcast at once.
 *
 * **We never duck.** `setWillPauseWhenDucked(true)` asks the platform to send a transient *loss*
 * instead of a duck request, because a monitor quietly turned down is worse than one that says it
 * has paused - a parent hearing faint audio has no way to tell whether the room is quiet or the
 * volume is.
 */
class AudioFocusOwner(
    private val audioManager: AudioManager,
    private val onLoss: (transient: Boolean) -> Unit,
    private val onGain: () -> Unit,
) {

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(LOG_TAG, "audio focus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onGain()
            AudioManager.AUDIOFOCUS_LOSS -> onLoss(false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> onLoss(true)
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener)
        .build()

    /**
     * Returns whether focus was granted - but the caller should carry on either way.
     *
     * Focus is cooperative, not enforced: playback still works without it. Refusing to monitor
     * because the request was declined would turn an advisory protocol into a hard failure of the
     * one thing the user asked for. Android 15+ can decline it outright unless the app is top or a
     * foreground service, which is why this is only ever requested from [MonitoringService].
     */
    fun request(): Boolean {
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Log.w(LOG_TAG, "audio focus request declined - continuing anyway")
        return granted
    }

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
