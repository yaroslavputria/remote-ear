# Technical feasibility assessment

> Deliverable 1 of the project brief (§21). Answers the ten Phase 1 questions from §18.
>
> **Status:** desk research. Every claim marked *(measure)* is a hypothesis that must be confirmed on
> physical hardware — see [test-matrix.md](test-matrix.md). Nothing in this document has been
> observed on a device yet.

## Verdict

**Feasible**, and not marginally so — this is a supported configuration on modern Android rather
than a trick. But the feasibility rests on one decision, and getting it wrong turns the product from
"works" into "sounds like a 1990s phone call and steals the earbud's microphone":

> **Stay entirely on Android's *media* audio path. Never touch the *communication* audio path.**

That is recorded as [ADR-0004](adr/0004-media-path-only.md) and is the single most load-bearing
constraint in the project.

The brief (§5) warns against assuming that "simply opening `AudioRecord` and `AudioTrack` is
sufficient". The research says: opening them is *nearly* sufficient — the pipeline itself is simple.
The difficulty is not in the audio primitives but in four things around them:

1. not accidentally requesting the communication path (Q3 below),
2. the foreground-service contract that keeps microphone access alive in the background (Q6),
3. OEM behaviour — process killing and microphone signal processing (Q8),
4. long-run clock drift between the phone's microphone and the earbud's DAC (see
   [audio-pipeline.md](audio-pipeline.md)).

---

## Q1. Can Android reliably capture from the built-in microphone while outputting to Bluetooth headphones?

**Yes.**

The reason is a property of the Bluetooth profiles themselves. **A2DP is a sink-only profile** — it
carries a compressed audio stream from phone to headphones and has no return path. It therefore
neither requires nor implies HFP/HSP (the hands-free profile that carries a microphone), and an
active A2DP output stream places no claim on the phone's own microphone.

So the combination:

- `AudioRecord` with `AudioSource.MIC`, input pinned to `AudioDeviceInfo.TYPE_BUILTIN_MIC`, and
- `AudioTrack` with `AudioAttributes.USAGE_MEDIA`, output landing on `TYPE_BLUETOOTH_A2DP`

is two independent streams that happen to run at the same time. Android has no policy objecting to
it — any app that records video while music plays to headphones is doing something comparable.

**The real failure mode is not impossibility — it is the app asking for the wrong thing.** If the
app requests the *communication* audio path, Android reconfigures the Bluetooth link to HFP/SCO:
mono, 8 or 16 kHz, and the **headset's microphone becomes the system input**, which is precisely
what brief §6 forbids. This does not happen by accident from `AudioRecord(MIC)`; it happens from the
calls listed in Q3.

*(measure)* That no OEM audio policy demotes or reconfigures A2DP merely because a capture stream is
active. This is the first thing the Phase 2 prototype logs.

## Q2. What APIs should be used?

| Concern | API | Notes |
|---|---|---|
| Capture | `AudioRecord.Builder` + `AudioSource.MIC` | `UNPROCESSED` is a candidate alternative — see below |
| Playback | `AudioTrack.Builder` + `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH)` | `USAGE_VOICE_COMMUNICATION` is **forbidden** |
| Input pinning | `AudioRecord.setPreferredDevice(builtInMic)` | API 24 |
| Output pinning | `AudioTrack.setPreferredDevice(bluetoothSink)` | API 24 |
| Routing proof | `getRoutedDevice()` on both | Assert *after* start; a preference is a request, not a guarantee |
| Bluetooth presence | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` filtered to `TYPE_BLUETOOTH_A2DP`, `TYPE_BLE_HEADSET` | Needs **no** Bluetooth permission |
| Connect / disconnect events | `AudioManager.registerAudioDeviceCallback` | API 23; preferable to Bluetooth broadcasts, and permission-free |
| Interruptions | `AudioManager.requestAudioFocus(AudioFocusRequest)` | API 26; `AUDIOFOCUS_GAIN` with pause-when-ducked |
| Background execution | `Service` with `foregroundServiceType="microphone"` | API 29 |
| Output level | `AudioTrack.setVolume(0f..1f)` | Per-track gain; never change system stream volume |

Note the consequence of using `AudioManager.getDevices()` for Bluetooth detection: the **device
type** (`TYPE_BLUETOOTH_A2DP`) is available without `BLUETOOTH_CONNECT`. Only the human-readable
product *name* requires that permission. Since the UI needs a status dot and not a device name, the
app can satisfy functional requirement 2 of brief §4 ("detect whether a usable Bluetooth audio
output is connected") while requesting **no Bluetooth permission at all**. See
[ADR-0007](adr/0007-minimal-permission-set.md).

### Input source: `MIC` vs `UNPROCESSED`

This choice has real product consequences and cannot be settled from documentation.

- `MIC` applies the device's tuned input processing chain — typically automatic gain control and
  noise suppression. For a baby monitor this can be actively harmful: aggressive AGC/NS is designed
  to isolate a nearby talker and may **gate away exactly the quiet room sounds the user wants to
  hear** (breathing, rustling, a distant whimper), then pump loudly when the child does cry.
- `UNPROCESSED` (API 24, and only when `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`
  reports true) gives a flat, unprocessed signal — more faithful to the "natural environmental
  sound" priority in brief §4, but quieter, requiring software gain.

**Decision:** default to `MIC` for MVP compatibility, and build an A/B toggle into the Phase 2
prototype so the two can be compared on real hardware in a real quiet room. *(measure)* — this is
[risk R2](risks.md), one of the two risks that can quietly destroy the product's value.

## Q3. What happens to Bluetooth profiles when microphone capture starts?

**Nothing — provided the app stays on the media path.**

The Bluetooth profile changes when the *audio mode* or *communication device* changes, not when a
recording stream opens. The specific triggers to avoid:

| Trigger | Effect |
|---|---|
| `AudioManager.startBluetoothSco()` | Establishes an SCO link — narrowband, headset mic becomes input |
| `AudioManager.setMode(MODE_IN_COMMUNICATION)` | Switches the whole device to the comms path |
| `AudioManager.setCommunicationDevice(bluetooth)` (API 31+) | The modern equivalent of the above |
| `AudioManager.setSpeakerphoneOn(true)` | Comms-path routing control |
| `AudioSource.VOICE_COMMUNICATION` | Requests the comms uplink; pulls in platform AEC/AGC and, on some OEMs, comms routing |
| `AudioSource.VOICE_RECOGNITION` | Safer than the above, but still a special-cased path on some OEMs |
| `USAGE_VOICE_COMMUNICATION` on the output | Marks the output as a call; invites comms routing |

None of these are needed for this product. All of them are prohibited by
[ADR-0004](adr/0004-media-path-only.md).

**LE Audio is the genuine open question.** *(measure)* Bluetooth LE Audio (LC3) is architecturally
different from A2DP: it is bidirectional by design and organised around *audio contexts*. There is a
plausible failure mode in which the stack decides that an active capture stream means a
"conversational" context, engages the earbud's microphone, and reconfigures the media stream at
lower quality — reproducing the exact behaviour §6 forbids, without the app doing anything wrong.
This must be tested on real LE Audio hardware (Pixel Buds Pro, Galaxy Buds class) and is tracked as
[risk R4](risks.md). If it proves unavoidable, the mitigation is to detect `TYPE_BLE_HEADSET` and
either prefer an A2DP fallback or tell the user plainly.

## Q4. What is the expected latency?

Brief §14 accepts "a few hundred milliseconds". The budget:

| Stage | Typical | Controlled by |
|---|---|---|
| Microphone capture buffer | 20–40 ms | Us — buffer sizing |
| Application read/write loop | 10–20 ms | Us — one frame |
| `AudioTrack` buffer | 20–60 ms | Us — buffer sizing |
| Android mixer + A2DP encode | 20–40 ms | Platform |
| **Bluetooth link + codec + earbud jitter buffer** | **100–250 ms** | **The headphones** |
| **Total** | **≈180–400 ms** | typically around 250 ms |

Two conclusions follow.

**The dominant term is not ours.** Well over half the latency lives inside the earbud's receive
jitter buffer and codec, and no amount of application-side work reduces it. SBC and AAC are the
worst offenders (AAC is often the worst on Android); aptX Low Latency and LC3/LE Audio can bring the
link term down to roughly 40–70 ms. *(measure)* per headphone class — see
[test-matrix.md](test-matrix.md).

**Therefore Oboe/AAudio is not worth it for the MVP.** A low-latency native path could shave perhaps
20–40 ms off the application-side terms, against a 100–250 ms term it cannot touch — roughly a 10%
change in total latency, for the cost of an NDK build, a C++ layer, and a new class of crash. A2DP
output typically will not grant a fast mixer path anyway. Deferred in
[ADR-0003](adr/0003-audiorecord-audiotrack-for-mvp.md).

Measurement method for Phase 2: clap near the phone while recording the earbud output on a second
device, then measure the offset between the two transients in any audio editor. Subjective judgement
("does it feel laggy holding a conversation through it") is also a legitimate signal for this
product.

## Q5. Can it work while the screen is locked?

**Expected yes.** Two things are commonly conflated here:

- **Screen off is not Doze.** Doze requires the device to be stationary, unplugged, and idle for a
  sustained period. Locking the screen and walking into the next room does not immediately enter
  Doze.
- **A foreground service is largely exempt from Doze anyway**, and Doze's headline restrictions
  (network access, deferred jobs and alarms) are irrelevant to a local audio loop. An actively
  playing `AudioTrack` keeps the audio path and the associated CPU work alive.

So on AOSP-like Android this should simply work. The residual risk is not the platform — it is
**OEM battery managers killing the process** (Q8, [risk R1](risks.md)). *(measure)* Scenarios C and
G, per OEM.

Deliberately **not** planned: requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. It is a
Play-policy-sensitive permission, and for this app the honest mitigation is a short piece of in-app
guidance pointing the user at their own battery settings once a kill has actually been observed.

## Q6. What foreground service configuration is required?

Manifest:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".monitor.MonitoringService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

The rules that matter, in the order they bite:

1. **`RECORD_AUDIO` must already be granted** before promoting the service with
   `FOREGROUND_SERVICE_TYPE_MICROPHONE`. On API 34+, starting a microphone-type foreground service
   without the underlying permission raises a `SecurityException`. Permission first, service second.
2. **Start from a visible Activity.** Since API 31 an app cannot start a foreground service from the
   background. This is why the user presses START while looking at the app.
3. **`startForeground()` must be called promptly** (within a few seconds) after
   `startForegroundService()`, or the system raises an ANR-class failure.
4. **A visible, ongoing notification** is required by the platform and desirable regardless: a
   continuously-listening microphone should be conspicuous. Low-importance channel, no sound, with a
   `Stop` action.

**The consequence that shapes the design:** because a background app cannot start a foreground
service, "resume monitoring when the headphones reconnect" is only achievable if the service is
**still running** in a paused state. If the service stops when Bluetooth drops, it cannot restart
itself from the background and auto-resume becomes impossible. Hence
[ADR-0005](adr/0005-foreground-service-hosts-monitoring.md): the service outlives the audio streams
and owns a `Paused` state.

> **Open verification task.** These rules reflect the platform through Android 14/15. Android has
> introduced foreground-service *timeouts* for certain service types and further background-start
> restrictions in recent releases. Before any Phase 2 code is written, re-check the current
> `developer.android.com` documentation for the `microphone` type against `targetSdk 36`. Tracked as
> Phase 1 of the [implementation plan](implementation-plan.md).

## Q7. Which Android versions support the required APIs?

| API | Since | Needed for |
|---|---|---|
| `AudioRecord`, `AudioTrack` | 3 | the pipeline |
| `AudioDeviceCallback`, `AudioDeviceInfo` | 23 | Bluetooth presence and hotplug |
| `setPreferredDevice()` | 24 | pinning input to the built-in mic |
| `AudioSource.UNPROCESSED` | 24 | optional natural-sound input |
| `AudioFocusRequest` | 26 | interruption handling |
| `foregroundServiceType` | 29 | microphone access in the background |
| `AudioDeviceInfo.TYPE_BLE_HEADSET` | 31 | LE Audio earbuds |
| `POST_NOTIFICATIONS` | 33 | showing the ongoing notification |
| `FOREGROUND_SERVICE_MICROPHONE` | 34 | declaring the FGS type |

**`minSdk 29` is the natural floor**: it is the first level with `foregroundServiceType`, and
everything above it degrades into two small version guards (`TYPE_BLE_HEADSET`,
`POST_NOTIFICATIONS`) rather than into alternative architectures. Below 29 the background-microphone
story is materially different, which is exactly the legacy complexity brief §15 asks to avoid. See
[ADR-0002](adr/0002-min-and-target-sdk.md).

## Q8. What are the known OEM and device-specific problems?

Ranked by how much damage they do to *this particular* product:

1. **Aggressive process killing.** Xiaomi (MIUI/HyperOS), Huawei (EMUI), Samsung ("Put unused apps
   to sleep"), and Oppo/Vivo/OnePlus (ColorOS/Funtouch) all kill background processes more eagerly
   than AOSP, foreground service or not. For a monitor that must survive hours, a silent kill is the
   worst possible bug — the user believes they are listening and they are not. Mitigations: detect
   and surface it, keep the notification informative, guide the user to their own battery settings,
   and consider a heartbeat that makes a death visible.
2. **Input processing gating quiet sound.** Discussed in Q2. Some OEMs apply heavy AGC and noise
   suppression to `MIC`. A gate that suppresses room noise is fatal to the product's value.
3. **Single-microphone-holder preemption.** Since Android 10 the microphone is not shared freely: a
   higher-priority client (an assistant, a call, the camera on some devices) can take it. The app
   receives silence or a read error rather than a tidy callback. `AudioRecord.read()` returning
   `ERROR_DEAD_OBJECT` or `ERROR_INVALID_OPERATION`, or a sustained run of exactly-zero frames, must
   be treated as "the microphone was taken" and surfaced honestly.
4. **LE Audio divergence.** See Q3.
5. **A2DP absolute-volume quirks.** Some earbuds map the phone's volume scale coarsely or ignore it,
   so `setVolume()` may feel steppy or have little effect at the low end.
6. **Codec-dependent latency.** The same phone with two different earbuds can differ by 150 ms.

None of these are solvable by cleverness. They are solvable by testing on the real matrix and by
degrading honestly when something is wrong.

## Q9. Can this be implemented reliably with React Native + Kotlin?

**Yes, reliably — but pointlessly.** The realtime pipeline, the foreground service, audio routing,
device enumeration, and audio focus are all native Kotlin in either design. React Native's share of
the work would be the screen in brief §9: two status dots, one button, one slider. The JavaScript
thread would never touch an audio sample.

## Q10. Would native Kotlin be materially simpler?

**Yes.** It removes a Node/Metro toolchain, TurboModule codegen and its bridge boilerplate, roughly
20–40 MB of APK, a slower cold start, and a whole category of failure modes — in exchange for
writing about 150 lines of Compose instead of about 150 lines of TSX. For a privacy-sensitive
microphone app there is also a supply-chain argument: fewer third-party dependencies is a smaller
attack surface and a shorter list of things to justify.

Decided in [ADR-0001](adr/0001-native-kotlin-over-react-native.md).

---

## Answerable only on hardware

Everything above marked *(measure)*, consolidated. These feed [test-matrix.md](test-matrix.md) and
gate Phase 2:

| # | Question | Detected by |
|---|---|---|
| H1 | Does A2DP survive unchanged while an `AudioSource.MIC` stream is active, on every test device? | Scenario A + `getRoutedDevice()` assertions |
| H2 | Does LE Audio switch to a bidirectional context and engage the earbud mic? | Scenario A on LE Audio hardware |
| H3 | Real end-to-end latency per headphone class | Clap test, Scenarios A and B |
| H4 | Does `MIC` processing gate away quiet room sound where `UNPROCESSED` does not? | A/B toggle in a real quiet room |
| H5 | Does the service survive hours with the screen locked, on each OEM? | Scenarios C and G |
| H6 | Actual battery drain per hour | Scenario G with `batterystats` |
| H7 | How much clock drift accumulates, and in which direction? | Scenario G with underrun and lag counters |
| H8 | What does the pipeline observe when a call or an assistant takes the microphone? | Scenario F |

**The emulator cannot answer any of these** — it has no Bluetooth audio. All of it is
physical-device work.
