---
name: remote-ear-audio
description: >
  RemoteEar's audio-path invariants. Use for ANY work touching microphone capture, audio playback,
  Bluetooth or audio routing, audio device selection, audio focus, the monitoring foreground
  service, buffer sizing, or clock drift — including reviewing such code. Trigger on AudioRecord,
  AudioTrack, AudioManager, AudioDeviceInfo, AudioAttributes, AudioFocusRequest,
  AcousticEchoCanceler, setPreferredDevice, getRoutedDevice, A2DP, SCO, HFP, LE Audio, LC3,
  foreground service, foregroundServiceType, RECORD_AUDIO, microphone permission, latency, underrun,
  buffer size, sample rate, drift, "monitoring", "routing", "mic to Bluetooth". These rules exist
  because the failure modes are silent — the app appears to work while using the wrong microphone or
  quietly not listening at all.
---

# RemoteEar audio path

RemoteEar captures the phone's **built-in** microphone and plays it to **Bluetooth headphones**. The
pipeline is trivial; the constraints around it are not, and getting one wrong produces a bug you
cannot hear.

Authoritative documents: [ADR-0004](../../../docs/adr/0004-media-path-only.md) and
[docs/audio-pipeline.md](../../../docs/audio-pipeline.md). This skill is the short version, plus the
rules most likely to be broken by plausible-looking code.

## The one rule

> **Stay on the media path. Never touch the communication path.**

Android has two audio paths. **Media** (`USAGE_MEDIA` → A2DP/LC3) is high quality and output-only,
so a microphone capture stream is simply independent of it. **Communication** (HFP/SCO) is
bidirectional, mono, 8–16 kHz, and **makes the headset's microphone the system input** — which
destroys the product, because the phone in the child's room stops being the listening device.

The switch is not triggered by opening an `AudioRecord`. It is triggered by changing the audio
*mode* or the *communication device*. So this failure does not happen spontaneously; it happens when
code asks for it while trying to "make Bluetooth audio work".

### Never call, never use

| Forbidden | What it does |
|---|---|
| `AudioManager.startBluetoothSco()` / `stopBluetoothSco()` | Establishes SCO — headset mic becomes input |
| `AudioManager.setMode(MODE_IN_COMMUNICATION)` / `MODE_IN_CALL` | Switches the device to the comms path |
| `AudioManager.setCommunicationDevice()` (API 31+) | Modern equivalent |
| `AudioManager.setSpeakerphoneOn()` | Comms-path routing control |
| `AudioAttributes.USAGE_VOICE_COMMUNICATION` | Marks output as a call; invites comms routing |
| `AudioSource.VOICE_COMMUNICATION` | Requests the comms uplink; adds AGC/NS that fight natural room sound |
| `AudioSource.VOICE_RECOGNITION` | Still special-cased on some OEMs |
| `MODIFY_AUDIO_SETTINGS` permission | Only needed for the above. **Needing it means something is wrong** |

If a task seems to require any of these, **stop and say so** rather than working around ADR-0004.
That ADR is the product requirement in brief §6, not a preference.

## Correct construction

```kotlin
// Input — the phone's own microphone, pinned.
val record = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.MIC)          // or UNPROCESSED, see below
    .setAudioFormat(AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(48_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build())
    .setBufferSizeInBytes(recordBufferBytes)
    .build()
    .apply { preferredDevice = builtInMic }                 // TYPE_BUILTIN_MIC

// Output — media path to the Bluetooth sink, pinned.
val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)              // NOT USAGE_VOICE_COMMUNICATION
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build())
    .setAudioFormat(/* 48 kHz, mono, PCM 16-bit — same as input */)
    .setBufferSizeInBytes(trackBufferBytes)
    .setTransferMode(AudioTrack.MODE_STREAM)
    .build()
    .apply { preferredDevice = bluetoothSink }              // TYPE_BLUETOOTH_A2DP or TYPE_BLE_HEADSET
```

## Assert routing; never assume it

`setPreferredDevice()` is a **request**. OEM audio policy can decline it. After
`startRecording()` and `play()`:

```kotlin
val inType  = record.routedDevice?.type
val outType = track.routedDevice?.type
```

- `inType` **must** be `TYPE_BUILTIN_MIC`.
- `outType` **must** be `TYPE_BLUETOOTH_A2DP` or `TYPE_BLE_HEADSET`.
- **`TYPE_BLUETOOTH_SCO` anywhere is a hard error** — fail loudly, log both observed types, tell the
  user. Do not continue in a "degraded mode": it means audio is arriving from the wrong microphone,
  which sounds fine and is wrong.

Details and `adb` verification: [references/routing-verification.md](references/routing-verification.md).

## Device selection

Filter `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` to `TYPE_BLUETOOTH_A2DP` and (API 31+)
`TYPE_BLE_HEADSET`. Explicitly exclude `TYPE_BLUETOOTH_SCO`.

**This needs no Bluetooth permission.** Device *types* are readable without `BLUETOOTH_CONNECT`;
only the human-readable *name* requires it, and the UI needs a status dot, not a name. Do not add a
Bluetooth permission to make status text nicer — see
[ADR-0007](../../../docs/adr/0007-minimal-permission-set.md).

Use `AudioManager.registerAudioDeviceCallback` for connect/disconnect, not Bluetooth broadcasts:
permission-free and closer to what the audio system actually decided.

## Format and buffers

48 kHz · mono · PCM 16-bit · **on both ends** · ~20 ms frames (960 frames = 1920 bytes) · buffers at
`max(minBufferSize * 2, frameBytes * 4)`.

- **Both ends identical**, or the platform silently inserts a resampler.
- Check `getMinBufferSize()` for `ERROR_BAD_VALUE`.
- Do not "optimise" to 16 kHz — it saves nothing measurable and costs the natural-room-sound quality
  the product is for.
- Do not shrink buffers to the platform minimum — 20–60 ms saved against a 100–250 ms earbud jitter
  buffer, in exchange for glitches.

→ [ADR-0006](../../../docs/adr/0006-audio-format-and-buffering.md)

## The loop: dedicated thread, zero allocation

```kotlin
Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
val buffer = ShortArray(960)              // allocated once, before the loop
while (running) {
    val read = record.read(buffer, 0, buffer.size)   // blocking
    if (read <= 0) { onReadFailure(read); return }
    track.write(buffer, 0, read)                     // blocking
}
```

**A plain `Thread`, not a coroutine dispatcher.** This is a deliberate departure from the project's
coroutines-by-default posture:

- The loop is an unbounded sequence of *blocking* calls — nothing to suspend on.
- It needs a priority the platform's audio scheduling recognises. `Dispatchers.IO` threads are shared
  and reprioritised.
- Occupying a shared dispatcher thread forever is antisocial.

Coroutines and `StateFlow` remain correct for everything *around* the loop: service lifecycle, the
state the UI observes, permissions, device callbacks.

**Nothing allocates inside the loop.** One reused buffer. No per-frame logging, no string formatting,
no boxing, no capturing lambdas. A GC pause is an audible click.

## Interruptions

Request `AUDIOFOCUS_GAIN` once at start, with `setWillPauseWhenDucked(true)` — **pause rather than
duck**. A ducked baby monitor is quiet enough to be useless and loud enough to seem fine.

**Request focus from inside the running foreground service, after `startForeground()` succeeds.**
Targeting Android 15+, an app must be top app *or* running a foreground service to obtain focus at
all; otherwise the call just returns `AUDIOFOCUS_REQUEST_FAILED`. Requesting speculatively from the
Activity is an ordering bug that presents as an unexplained silent failure.

| Event | Response |
|---|---|
| `AUDIOFOCUS_LOSS_TRANSIENT` | `Paused(AudioFocusLost)`, resume on `AUDIOFOCUS_GAIN` |
| `AUDIOFOCUS_LOSS` | Stop; require an explicit restart |
| Phone or VoIP call | `Paused(Call)`. Never interfere with call audio. We lose the microphone anyway — see below — so continuing would deliver silence while claiming to monitor |
| Bluetooth removed | `Paused(BluetoothGone)`; auto-resume on reconnect |
| **`isClientSilenced()` reports true** | `Paused(MicPreempted)`; retry with backoff, then surface. `read()` errors and sustained zero-frame runs are backstops, not the mechanism |

### Losing the microphone looks like silence, not an error

*(verified)* Android's concurrent-capture policy means **two ordinary apps can never capture at the
same time**, and the loser keeps receiving buffers of **zeros** — no exception, no failed call. The
system microphone privacy toggle behaves the same way. In a monitor this is the most dangerous
failure shape there is, because a quiet room also sounds like nothing.

**Detect it with the platform API:**

```kotlin
// MUST be registered before capture starts.
record.registerAudioRecordingCallback(executor, object : AudioManager.AudioRecordingCallback() {
    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
        // configs.any { it.isClientSilenced } -> Paused(MicPreempted)
    }
})
```

`isClientSilenced()` is available from API 29 — exactly our `minSdk` floor, so no version guard.

**We sit below VoIP apps in the priority order and cannot fix that.** Only `CAMCORDER` and
`VOICE_COMMUNICATION` are *privacy-sensitive* sources, and they win "even if [the other app] has a UI
on top or started capturing more recently". `AudioSource.MIC` is not privacy-sensitive. Do **not**
try to climb that ranking by switching source — that would violate ADR-0004 and break the product.
The foreground service already buys foreground-equivalent priority against other ordinary apps,
which is the common case.

**`Paused` always carries a reason, and it is shown in both the UI and the notification.** Never a
silent stop, and never a notification that says "Monitoring" while the streams are closed. For this
product, a user who believes they are listening when they are not is the worst possible outcome.

## Volume

- Output level: **`AudioTrack.setVolume(0f..1f)`** — per-track, instant, permission-free.
- **Never** `adjustStreamVolume` / `setStreamVolume`. Changing a global device setting from a utility
  app is user-hostile and outlives the session.
- Making a quiet room *louder* needs a software multiply plus a limiter (`setVolume` caps at 1.0).
  That is a "should have", not a must — see [docs/mvp-scope.md](../../../docs/mvp-scope.md).

## Input source: `MIC` vs `UNPROCESSED`

`MIC` runs the device's tuned input chain — often aggressive AGC and noise suppression tuned to
isolate a nearby talker. For a baby monitor that can **gate away exactly the quiet room sounds the
user wants**, then pump when the child cries. `UNPROCESSED` (only if
`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` reports true) is flat and more faithful,
but quieter.

Default `MIC`; keep the A/B toggle. This is *(unverified)* on real hardware and is
[risk R2](../../../docs/risks.md) — one of two risks that can make the product pointless.

## Privacy invariants

Structural, not aspirational:

- The reused buffer is the **only** place audio exists. It is overwritten every 20 ms.
- **No audio to any file, database, cache, or log.** Frame counters yes; frame contents never.
- No `INTERNET` permission exists, so no network path exists to misuse. Do not add one.

## Clock drift

The phone's ADC and the earbud's DAC run on independent crystals — at 100 ppm that is ~6 ms/minute,
~360 ms/hour of accumulated offset. It shows up only in multi-hour runs, as growing latency or
accumulating clicks, worsening the longer the monitor runs.

Blocking read → blocking write bounds the symptom. Instrument `getUnderrunCount()`, block times, and
frames in versus out; correct coarsely by dropping or padding **one frame** at a threshold. **No
resampler** unless measurement demands it.

## Foreground service

Rules, sequencing, notification spec, and the reason the service must stay alive while paused:
[references/foreground-service.md](references/foreground-service.md).

The short version: `RECORD_AUDIO` before promotion; start from a visible Activity; the service owns
a `Paused` state and does not stop when audio cannot flow — because a background app cannot start a
foreground service, so a stopped service can never auto-resume.

## Verifying anything

**The emulator has no Bluetooth audio and can verify none of this.** Physical device only — use the
`remote-ear-device-test` skill.
