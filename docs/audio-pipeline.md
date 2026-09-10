# Audio pipeline

> Deliverable 3 of the project brief (§21): recommended APIs and data flow.
>
> Read [ADR-0004](adr/0004-media-path-only.md) first. Everything here assumes the media-path-only
> rule; several choices below make no sense without it.

## Signal chain

```text
  built-in microphone
          |                     AudioSource.MIC  (or UNPROCESSED)
          v                     pinned: setPreferredDevice(TYPE_BUILTIN_MIC)
  +---------------+
  |  AudioRecord  |  48 kHz / mono / PCM 16-bit
  +-------+-------+
          |
          |  blocking read into one reused ShortArray  (~20 ms frame)
          v
  +---------------+
  | monitor thread|  THREAD_PRIORITY_URGENT_AUDIO, zero allocation
  +-------+-------+
          |
          |  blocking write
          v
  +---------------+
  |  AudioTrack   |  48 kHz / mono / PCM 16-bit
  +-------+-------+  USAGE_MEDIA + CONTENT_TYPE_SPEECH
          |          pinned: setPreferredDevice(TYPE_BLUETOOTH_A2DP | TYPE_BLE_HEADSET)
          v
  Android mixer -> A2DP/LC3 encoder -> Bluetooth link
          |
          v
      (o) earbud
```

There is deliberately **no** DSP, resampler, ring buffer, or queue between the two ends. The read
and write are on the same thread, back to back. Every box that is not in this diagram is latency,
battery, and a place for bugs to live.

## Format

| Parameter | Value | Why |
|---|---|---|
| Sample rate | **48 000 Hz** | The native rate of essentially all modern Android audio hardware, so nothing resamples. 16 kHz would save negligible CPU and cost the "natural environmental sound" priority in brief §4 |
| Channels | **mono** | One built-in microphone is the input. Stereo would duplicate data for no information. `AudioTrack` handles mono-to-stereo for the sink |
| Encoding | **PCM 16-bit** | Sufficient dynamic range for a room; float adds nothing before the A2DP encoder throws it away |
| Frame | **≈20 ms = 960 frames = 1920 bytes** | Small enough for latency, large enough that per-call overhead is irrelevant |

Throughput is therefore **96 kB/s** — about 0.1 MB/s of memory copying. The CPU cost of the pipeline
itself is negligible; battery goes to the microphone, the audio DSP path, and the Bluetooth radio
(see [risks.md](risks.md) R8).

**Both ends must use the same rate and channel count.** A mismatch silently inserts a platform
resampler, which adds latency and CPU for no benefit. If some device refuses 48 kHz, fall back
explicitly and log it rather than letting the two ends drift apart.

## Buffer sizing

```kotlin
val frameBytes = 1920                       // 20 ms mono 16-bit @ 48 kHz
val minRecord  = AudioRecord.getMinBufferSize(48_000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
val minTrack   = AudioTrack.getMinBufferSize(48_000, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT)

val recordBufferBytes = maxOf(minRecord * 2, frameBytes * 4)   // ~80 ms
val trackBufferBytes  = maxOf(minTrack,      frameBytes * 4)   // ~215 ms on A2DP
```

**The asymmetry is deliberate — do not "tidy" it into symmetry.** It is the correction in
[ADR-0009](adr/0009-buffer-sizing-measured.md), made after measuring on hardware.

On the **capture** side, `getMinBufferSize` really is the smallest buffer that *can* work rather than
one that works reliably under scheduler pressure, and doubling it costs ~40 ms — cheap insurance.

On the **A2DP output** side the reasoning inverts. Measured on a real route, `minTrack` came back as
**20 622 bytes ≈ 215 ms**: the platform has already budgeted generously for the Bluetooth link.
Doubling that bought no additional glitch resistance and added another ~215 ms of latency — which
was, until it was found, the single largest app-side contributor to end-to-end delay.

Two consequences worth remembering:

- **`minTrack` is route-dependent.** A speaker or wired route reports a far smaller minimum, so the
  same expression yields a much smaller buffer there. That is correct, but it means the buffer size
  must be **logged every session** rather than assumed constant.
- Both values must still be checked against `ERROR_BAD_VALUE`.
- Output underrun risk is now the platform's judgement rather than ours, and that is *(unverified)*
  over long runs. Scenario G must watch `getUnderrunCount()`; if underruns appear the answer is a
  modest 1.25–1.5× multiplier, not a return to 2×.

## The monitor thread

**The realtime loop runs on a plain `Thread` at `THREAD_PRIORITY_URGENT_AUDIO`, not on a coroutine
dispatcher.** This deliberately departs from the project's normal coroutines-everywhere default, for
three reasons:

- The loop is an unbounded sequence of *blocking* calls. It is not structured-concurrency-shaped
  work; there is nothing to suspend on.
- It needs a thread priority that the platform's audio scheduling recognises. Coroutine dispatchers
  do not offer that, and `Dispatchers.IO` threads are shared and reprioritised.
- Occupying a shared dispatcher thread forever is antisocial, and a coroutine cancellation check is
  a worse stop signal than closing the streams.

Coroutines remain correct for everything *around* the loop: the service's lifecycle, the `StateFlow`
the UI observes, permission flows, device-callback plumbing.

```kotlin
// sketch — illustrates the invariants, not final code
private fun runLoop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    val buffer = ShortArray(960)                 // allocated once, before the loop
    while (running) {
        val read = record.read(buffer, 0, buffer.size)   // blocking
        if (read <= 0) { onReadFailure(read); return }
        track.write(buffer, 0, read)                     // blocking
    }
}
```

**Zero allocation inside the loop.** One buffer, reused. No logging per frame, no `String`
formatting, no boxing, no lambda capture. A garbage collection pause is an audible glitch.

## Device pinning, and proving it worked

Requesting a device is not the same as getting one. The platform treats `setPreferredDevice()` as a
*preference*, and OEM audio policy can override it.

```kotlin
record.preferredDevice = builtInMic          // TYPE_BUILTIN_MIC
track.preferredDevice  = bluetoothSink       // TYPE_BLUETOOTH_A2DP or TYPE_BLE_HEADSET
```

After `startRecording()` and `play()`, **assert the actual routing** and refuse to run silently in a
wrong configuration:

```kotlin
val inType  = record.routedDevice?.type
val outType = track.routedDevice?.type
// inType must be TYPE_BUILTIN_MIC — anything else, especially TYPE_BLUETOOTH_SCO,
// means the earbud microphone is the input, which brief section 6 forbids.
// outType must be TYPE_BLUETOOTH_A2DP or TYPE_BLE_HEADSET — never TYPE_BLUETOOTH_SCO.
```

`TYPE_BLUETOOTH_SCO` appearing on either end is a hard error, not a degraded mode: it means the link
has collapsed to the hands-free profile. Fail loudly, log the observed types, and tell the user
something is wrong rather than delivering narrowband audio from the wrong microphone.

Output device selection filters `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` to
`TYPE_BLUETOOTH_A2DP` and `TYPE_BLE_HEADSET`, explicitly excluding `TYPE_BLUETOOTH_SCO`. This needs
no Bluetooth permission — see [ADR-0007](adr/0007-minimal-permission-set.md).

## Clock drift

This is the failure that only appears in the multi-hour Scenario G run, and the reason that test
exists.

The phone's microphone ADC and the earbud's DAC are driven by **independent crystals**. Consumer
oscillators are typically accurate to tens or hundreds of parts per million. At 100 ppm and 48 kHz:

| Elapsed | Accumulated offset |
|---|---|
| 1 second | 4.8 samples (0.1 ms) |
| 1 minute | ~6 ms |
| 1 hour | ~360 ms |
| 6 hours | ~2 s |

One side produces samples faster than the other consumes them, indefinitely. Ignoring it means
either steadily growing latency and eventual capture overrun, or steadily accumulating underruns —
audible as periodic clicks that get worse the longer the monitor runs, which is precisely backwards
for this product.

**MVP policy — measure and nudge, do not resample:**

1. The blocking read then blocking write structure already back-pressures the whole pipeline to
   whichever clock is *slower*, so drift shows up as one bounded symptom instead of unbounded queue
   growth.
2. Instrument it. Track `AudioTrack.getUnderrunCount()`, wall-clock time spent blocked in read
   versus write, and frames in versus frames out. Log a summary periodically — not per frame.
3. Correct coarsely when a threshold is crossed: **drop one frame** if the pipeline is running long
   (capture ahead), **write one frame of silence** if underruns are climbing (playback ahead). A
   single 20 ms correction every few minutes is inaudible; the drift it corrects is not.
4. **No resampler in MVP.** Adaptive resampling is the correct long-term answer and a poor first
   answer. Revisit only if H7 measurements show the coarse correction is audible.

These counters are also the diagnostic surface for [test-matrix.md](test-matrix.md) Scenario G, so
build them in Phase 6 rather than retrofitting them under a failing long-run test.

## State machine

```text
                 start (from a visible Activity)
   Idle ------------------------------------> Starting
    ^                                            |
    |                                            | streams open, routing asserted
    | stop                                       v
    +----------------- Stopping <----------- Monitoring
                          ^                    |   ^
                          |                    |   |
                          |          interruption  | condition cleared
                          |                    v   |
                          +------------------ Paused(reason)
```

`Paused` reasons, each with its own user-visible message:

| Reason | Trigger | Recovery |
|---|---|---|
| `BluetoothGone` | `AudioDeviceCallback.onAudioDevicesRemoved` removes the last usable sink | Automatic on reconnect — possible **only because the service stays alive**, see [ADR-0005](adr/0005-foreground-service-hosts-monitoring.md) |
| `AudioFocusLost` | `AUDIOFOCUS_LOSS_TRANSIENT` | Automatic on `AUDIOFOCUS_GAIN` |
| `Call` | Focus loss during telephony, or `AudioManager.mode` reports a call | Automatic when the call ends |
| `MicPreempted` | **`AudioRecordingConfiguration.isClientSilenced()`** via a recording callback registered *before* capture starts. `read()` errors and sustained zero-frame runs are backstops | Retry with backoff; surface after repeated failure |
| `Error` | Anything else, including a wrong `routedDevice` | Manual — show what was observed |

The important property: **`Paused` is a first-class state with a stated reason, not a silent stop.**
For a monitor, believing you are listening when you are not is the worst outcome in the product.
Brief §16 asks for exactly this.

## Interruptions and audio focus

Request focus once, when monitoring starts:

- `AudioFocusRequest.Builder(AUDIOFOCUS_GAIN)` with the same `AudioAttributes` as the track.
- **Request it from inside the running foreground service, after `startForeground()` succeeds.**
  Apps targeting Android 15+ must be the top app *or* be running a foreground service to obtain
  focus at all; otherwise the call just returns `AUDIOFOCUS_REQUEST_FAILED`. Requesting speculatively
  from the Activity is an ordering bug that will look like a mysterious silent failure.
- `setWillPauseWhenDucked(true)` — **pause rather than duck.** A ducked baby monitor is a
  particularly bad object: quiet enough to be useless, loud enough to seem fine.
- On `AUDIOFOCUS_LOSS`: stop and require an explicit restart. On `AUDIOFOCUS_LOSS_TRANSIENT`: enter
  `Paused(AudioFocusLost)` and resume on `AUDIOFOCUS_GAIN`.
- Resuming from `Paused` re-requests focus safely, because the service is still running and therefore
  still qualifies.

**Phone and VoIP calls take the microphone away from us, by documented rule.** *(verified)*
Privileged apps outrank ordinary ones, and privacy-sensitive sources (`VOICE_COMMUNICATION`,
`CAMCORDER`) outrank non-privacy-sensitive ones like our `MIC` — so a cellular call wins as a
privileged client, and a VoIP call wins on source priority. Either way we are **silenced, not
errored**: continuing to run would relay zeros while reporting "Monitoring".

Pausing is therefore both the correct behaviour and the honest one — brief §16 reaches the same
conclusion. The app must never attempt to keep capturing through a call, and must never touch call
audio.

## Volume and gain

**Loudness belongs to the platform, not to this app.** Because playback uses
`USAGE_MEDIA` it rides `STREAM_MUSIC`, so the phone's media volume already scales what the listener
hears — from the physical buttons, and via A2DP absolute volume from the earbud's own controls too.
That is the natural control for this product: the phone is in another room, so the earbud is what
the user can actually reach.

- **The phone media volume is the only loudness control**, and the app merely *reads* it
  (`getStreamVolume` / `getStreamMaxVolume`, mirrored live with a `ContentObserver` on
  `Settings.System` — no permission needed).
- **There is deliberately no app-side volume.** `AudioTrack.setVolume()` caps at 1.0, so it could
  only ever *attenuate* — useless against the "too quiet" complaint this product actually gets —
  while a forgotten setting would silently cap how loud the earbud could get. The track is left at
  unity gain.
- **Never modify system stream volume** (`adjustStreamVolume`, `setStreamVolume`). Android own
  documentation advises against them because they change volume for *every* app, and doing so would
  drag in global audio state that [ADR-0007](adr/0007-minimal-permission-set.md) keeps out.
- Making the room *louder* than it is needs gain above unity with a limiter, applied in the loop
  ([mvp-scope.md](mvp-scope.md) S4) — a different mechanism, not a volume control.

*Note for testing:* the physical buttons only adjust **media** volume while media is actually
playing. With monitoring stopped they adjust the ringer instead, which makes "the buttons do
nothing" an easy false conclusion when testing idle.

## Privacy invariants of the pipeline

Structural, not aspirational — see [privacy.md](privacy.md):

- The `ShortArray` is the only place audio exists. It is overwritten every 20 ms.
- **No file, database, cache, or log ever receives audio samples.** Frame *counters* are fine; frame
  *contents* are not.
- There is no network code, and the app ships without the `INTERNET` permission, so no
  network path exists to misuse.
