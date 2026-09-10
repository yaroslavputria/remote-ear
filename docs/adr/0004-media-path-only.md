# ADR-0004 — Media audio path only: never Bluetooth SCO or the communication path

- **Status:** Accepted
- **Date:** 2026-09-09

> **The load-bearing decision in this project.** If only one document is read, read this one.

## Context

Brief §6 states the requirement unusually precisely:

```text
INPUT:   phone's built-in microphone
OUTPUT:  Bluetooth headphones
```

and explicitly **not**:

```text
Bluetooth headset microphone -> phone -> headphones
```

Android has two largely separate audio paths, and the difference decides whether the product works:

- **The media path.** `USAGE_MEDIA` output goes over **A2DP** (or LC3 for LE Audio) — high quality,
  and **output-only**. A2DP has no return channel, so it neither requires nor supplies a microphone.
  An `AudioSource.MIC` capture stream is simply a separate, independent stream.
- **The communication path.** Intended for calls. Output and input go over **HFP/SCO**: mono, 8 or 16
  kHz, and **the headset's microphone becomes the system input**.

The crucial insight is *what triggers the switch*. It is not opening an `AudioRecord`. It is
changing the audio **mode** or the **communication device**. So the failure the brief fears does not
happen spontaneously — it happens when the app asks for it, usually while trying to "make Bluetooth
audio work".

## Decision

**RemoteEar stays entirely on the media path. The following are prohibited anywhere in the
codebase:**

| Prohibited | Why |
|---|---|
| `AudioManager.startBluetoothSco()` / `stopBluetoothSco()` | Establishes the SCO link — narrowband, headset mic becomes input |
| `AudioManager.setMode(MODE_IN_COMMUNICATION)` (or `MODE_IN_CALL`) | Switches the entire device to the comms path |
| `AudioManager.setCommunicationDevice()` (API 31+) | The modern equivalent |
| `AudioManager.setSpeakerphoneOn()` | Comms-path routing control |
| `AudioAttributes.USAGE_VOICE_COMMUNICATION` on the output | Marks the output as a call; invites comms routing |
| `AudioSource.VOICE_COMMUNICATION` | Requests the comms uplink; pulls in platform AEC/AGC and, on some OEMs, comms routing |
| `AudioSource.VOICE_RECOGNITION` | Safer than the above, but still special-cased on some OEMs |
| `TYPE_BLUETOOTH_SCO` as an acceptable routed device | Its presence *is* the failure |

**Instead:**

- Capture with `AudioSource.MIC` (or `UNPROCESSED`), pinned to `TYPE_BUILTIN_MIC`.
- Play with `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`, pinned to `TYPE_BLUETOOTH_A2DP` or
  `TYPE_BLE_HEADSET`.
- **Assert `getRoutedDevice()` after starting both streams.** A preferred device is a request, not a
  guarantee; OEM policy can override it. `TYPE_BLUETOOTH_SCO` on either end is a hard error that
  fails loudly — not a degraded mode that plays on.

## Consequences

- The requirement in brief §6 is satisfied by construction rather than by hoping: the built-in
  microphone is the input, and full-quality A2DP is the output.
- Latency and audio quality are the best available on the platform. SCO would have made the product
  sound like a 1990s phone call.
- **Two permissions become unnecessary.** `MODIFY_AUDIO_SETTINGS` exists to change global audio state,
  which is now forbidden; and Bluetooth detection uses `AudioManager.getDevices()`, which exposes
  device *types* without `BLUETOOTH_CONNECT`. See
  [ADR-0007](0007-minimal-permission-set.md).
- **A useful diagnostic:** if a future change starts needing `MODIFY_AUDIO_SETTINGS`, or reaches for
  `setMode`, it is probably violating this ADR. Treat that as the signal it is.
- **Phone calls are handled by pausing, not by participating.** Since we never enter the
  communication path, a call simply takes audio focus and the microphone; the honest response is to
  pause. Brief §16 reaches the same conclusion, and it is correct regardless: by the documented
  capture-priority rules a call outranks us — as a privileged client, or on source priority for VoIP
  — so continuing would relay silence while reporting "Monitoring".
- **The sleep-sound feature (brief §23) loses its most obvious implementation.** Platform
  `AcousticEchoCanceler` is documented as reliable on the `VOICE_COMMUNICATION` path, which this ADR
  forbids. That is a real cost, accounted for in
  [ADR-0008](0008-sleep-sound-deferred.md) — where a prior routing blocker turns out to matter more
  anyway.
- **We accept a lower microphone priority.** *(verified 2026-09-09)* Android's concurrent-capture
  policy ranks `CAMCORDER` and `VOICE_COMMUNICATION` as *privacy-sensitive* sources, and those win
  "even if [the other app] has a UI on top or started capturing more recently". `AudioSource.MIC` is
  not privacy-sensitive, so **RemoteEar structurally loses the microphone to any VoIP app** — and
  loses it as *silence*, not as an error. This is a genuine cost of the decision, discovered only on
  verification. It is tolerable for two reasons: the foreground service still buys
  foreground-equivalent priority against other *ordinary* apps, which is the common case; and when a
  call is in progress we want to pause anyway. But it must be detected and reported via
  `isClientSilenced()` rather than assumed — see [risk R7](../risks.md).
- **Reading the audio mode was never prohibited; setting it is.** *(clarified 2026-09-10, no change
  of decision.)* The table above forbids `setMode(...)` — the call that hands the device to the
  communication path. `getMode()` has no such effect, needs no permission, and turns out to be the
  only way to tell a phone call from another app's music **without `READ_PHONE_STATE`**, which
  matters because Phase 5 must name the pause reason correctly and the two cases read identically as
  an audio-focus loss. `tools/check-invariants.sh` originally banned the constants `MODE_IN_CALL`
  and `MODE_IN_COMMUNICATION` outright, which also banned comparing against them; the guard is now
  on `setMode(` and on the Kotlin property assignment `.mode =`, which is strictly stronger against
  the thing the ADR actually prohibits.
- **Constrains LE Audio only partially.** *(unverified)* LE Audio is bidirectional by design, so a
  stack may engage the earbud microphone without the app touching anything on the prohibited list.
  This ADR cannot prevent that; the `getRoutedDevice()` assertion is what detects it. See
  [risk R4](../risks.md).

## Alternatives considered

**Use the communication path deliberately** (`MODE_IN_COMMUNICATION` + SCO). This is what many
"Bluetooth microphone" tutorials do, and it fails the requirement in the most direct way possible:
the headset's microphone becomes the input, so the phone in the child's room stops being the
listening device. It also delivers narrowband mono audio. Rejected outright — it is not a trade-off,
it is the wrong product.

**`VOICE_COMMUNICATION` capture with media output.** Superficially attractive: it would enable
platform AEC for the future sleep-sound feature. Rejected — it requests the comms uplink, brings
AGC and noise suppression that fight the "natural environmental sound" priority in brief §4, and on
some OEMs drags routing onto the comms path. Optimising the MVP for a deferred feature's
convenience is exactly the wrong direction.

**Use one earbud as the microphone and the other as the speaker.** *(Proposed and rejected
2026-09-10.)* An appealing idea — leave one bud near the child, wear the other, no phone in the room
— and impossible for three independent reasons:

1. A TWS pair presents to the phone as **one** Bluetooth endpoint. Left and right are not separately
   addressable through any public Android API; the buds negotiate that between themselves.
2. Any earbud microphone is reachable only over **HFP/SCO** — confirmed on the device, where the
   only Bluetooth entries Android exposes are `bt_sco_hs`, `bt_sco_carkit`, `bt_a2dp` and
   `ble_headset`, and **A2DP appears only as an output**. SCO is bidirectional and owns both
   directions once active, so one bud cannot be on SCO while the other receives A2DP.
3. Even if the profiles allowed it, the buds talk to the *phone*, not to each other. Both must stay
   in phone range, so the phone still has to be near the child — which removes the entire benefit.

**Reverse the direction: earbud microphone playing out of the phone speaker.** *(Proposed and
rejected 2026-09-10.)* Technically possible, and it costs this decision. It needs
`setCommunicationDevice()`/SCO, plus almost certainly `MODIFY_AUDIO_SETTINGS` which
[ADR-0007](0007-minimal-permission-set.md) excludes; the result is narrowband mono, materially worse
than the proven path; and routing output to the speaker while SCO capture is live is the same
unverified dual-routing problem that blocks
[ADR-0008](0008-sleep-sound-deferred.md). The product asymmetry settles it independently of the
platform: the earbud has the worse microphone, a 4–6 hour battery, must stay in phone range, and
would mean leaving a small battery-containing object in a child's room. The phone is the better
listener in every respect.

**Reconsider only if** LE Audio changes the picture — it is bidirectional by design and supports
richer topologies, so the "two earbuds" idea is not permanently foreclosed by physics, only by
today's Android API surface. That would need measurement on LE Audio hardware
([H2](../feasibility.md)) and a new ADR.

**Reconsider only if** measurement shows the media path cannot deliver the core use case on real
hardware — the Phase 2 go/no-go gate in the [implementation plan](../implementation-plan.md). That
would be a finding significant enough to reopen the product concept, not just this ADR.
