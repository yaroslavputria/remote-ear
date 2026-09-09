# Test matrix

> Deliverable 7 of the project brief (§21), covering the scenarios in §19 and the white-noise tests
> in §23.

## The emulator is useless here

The Android emulator has no Bluetooth audio. It cannot verify routing, latency, profile behaviour,
codec differences, battery, or OEM process killing — which is to say, it cannot verify anything this
project is actually uncertain about.

**Every audio claim must be verified on physical hardware.** The emulator is fine for Compose
layout and permission-flow plumbing, and for nothing else.

## Devices

| Class | Why it is on the list | Priority |
|---|---|---|
| **Pixel** (or another near-AOSP device) | The reference behaviour. If something fails here it is our bug, not an OEM's | Must |
| **Samsung** (One UI) | Largest install base; has its own battery manager and its own audio tuning | Must |
| **Xiaomi / Oppo / Vivo** | The aggressive-process-killer class. [Risk R1](risks.md) lives here | Must |
| A second, older device | Confirms `minSdk 29` really works, not just compiles | Should |

Android versions to cover: **10** (the `minSdk` floor), **13** (`POST_NOTIFICATIONS`), **14**
(`FOREGROUND_SERVICE_MICROPHONE`), and **15/16** (the target). Coverage comes from whichever
physical devices are available — do not substitute an emulator for a version.

## Headphones

Latency and profile behaviour are properties of the *headphones* at least as much as of the phone,
so this axis matters as much as the device axis.

| Class | Example | What it tests |
|---|---|---|
| **SBC-only budget earbuds** | Any cheap pair | The worst-case latency and the most common real-world case |
| **AAC** | AirPods-class | AAC on Android is frequently the *worst* latency case, despite being "better" |
| **aptX / aptX LL** | Sony, Qualcomm-based | The good case; establishes the achievable floor |
| **LE Audio / LC3** | Pixel Buds Pro, Galaxy Buds class | **[H2 and risk R4](risks.md)** — the bidirectional-context question. The single most important row |
| **Over-ear** | Any | Different power profile and reconnect behaviour |

Also test with **one earbud only, the other in the case** — that is the actual use case from brief
§2, and some earbuds change codec or profile behaviour in single-bud mode.

## Scenarios

From brief §19. Each needs a pass criterion and captured evidence; recipes for the `adb` commands
are in the `remote-ear-device-test` skill.

### A — Phone and earbuds in the same room

**Pass:** microphone audio is audible and intelligible in the earbud.
**Capture:** `routedDevice` types both ends, negotiated format and buffer sizes, full output-device
list, measured clap latency, subjective note on a quiet room.
**Answers:** [H1, H2, H3, H4](feasibility.md).

> The `routedDevice` check is the whole point of this scenario. Audio being audible is not
> sufficient: it could be audible *through the wrong microphone*. `TYPE_BLUETOOTH_SCO` anywhere is a
> failure even if it sounds fine.

### B — Phone behind one wall

**Pass:** stable audio at the real usage distance; no repeated dropouts over 10 minutes.
**Capture:** dropout count, subjective stability, distance and wall type.

### C — Phone screen locked

**Pass:** monitoring continues uninterrupted for at least 30 minutes with the screen locked.
**Capture:** service still alive, notification state, any logcat gap.

### D — App in the background

**Pass:** monitoring continues; returning to the app shows the correct state rather than a stale one.

### E — Bluetooth disconnects

**Pass:** the app enters `Paused(BluetoothGone)` promptly, the notification and UI both say why, and
nothing crashes. On reconnect it resumes automatically (S1) — or, if S1 is deferred, it tells the
user clearly how to resume.
**Capture:** time to detect, time to resume, state text at each step.

> Test disconnection **two ways**: turning the earbuds off, and walking out of range. They can
> produce different callback timing.

### F — Incoming phone call

**Pass:** monitoring pauses, the call is entirely unaffected, and monitoring resumes when the call
ends. The app must never touch call audio.
**Capture:** the focus-change codes observed, what `AudioRecord.read()` returned during the call,
resumption behaviour.
**Answers:** [H8](feasibility.md).

> Needs a second phone. Emulator `gsm call` cannot help, because there is no Bluetooth audio to
> interact with.

### G — Several hours of monitoring

The scenario that finds what the others cannot. **Pass:**

- runs for the full duration with no crash and **no silent stop**;
- audio quality at hour four is indistinguishable from minute one — no accumulating clicks, no
  growing delay ([clock drift](audio-pipeline.md));
- battery drain leaves the product usable for a night's sleep;
- no thermal complaint from the device.

**Capture:** `batterystats` before/after, drain per hour, underrun and drift counters, CPU, memory,
temperature, whether the process survived.
**Answers:** [H5, H6, H7](feasibility.md).

> Run this on the aggressive-killer device *specifically*. A Pixel passing Scenario G says little
> about a Xiaomi.

## Sleep-sound tests (post-MVP)

Only after [ADR-0008](adr/0008-sleep-sound-deferred.md)'s question (a) is answered affirmatively —
if the speaker and A2DP cannot carry two concurrent streams, there is nothing to test.

Then, per brief §23: white noise at low, medium and high speaker volume; speaker close to the
microphone and at varying orientations; multiple phone models; different room acoustics; **speech
over the noise**; **a crying child over the noise**; sudden environmental sounds; several hours
continuous.

The measurement that matters is not "is the white noise suppressed" but the ratio:

> **Does suppression remove the phone's own noise while leaving the child's voice intact?**

A filter that removes both is worse than no filter, because it silently defeats the product's
purpose.

## Recording results

One file per session in [`docs/test-runs/`](test-runs/), named `<YYYY-MM-DD>-<device>-<headphones>.md`,
starting from [`test-runs/TEMPLATE.md`](test-runs/TEMPLATE.md).

Write down what happened, including the boring passes and the things that were not tested. A test
matrix with unexplained gaps reads as full coverage months later, which is how a monitor ships
broken on a phone nobody happened to try.
