# Privacy

> Brief §12 and §17. Privacy is treated as a **structural property** of the app, not a policy
> document about it.

## The architecture

```text
  microphone  ->  one 1920-byte buffer in RAM  ->  Bluetooth headphones
```

That is the whole data path. Audio exists in a single reused buffer that is overwritten every 20
milliseconds, and it is never copied anywhere else.

## Invariants

Each of these is enforced by the *absence* of something, which is why they are checkable rather than
merely promised.

| Invariant | How it is enforced |
|---|---|
| No audio leaves the phone | **The app has no `INTERNET` permission.** There is no network path to misuse, deliberately or by accident |
| No audio is stored | No file, database, cache, or `MediaRecorder` anywhere in the app. The only audio buffer is in memory and is continuously overwritten |
| No audio is logged | Frame *counters* are logged; frame *contents* never are. This is a review rule, not a preference |
| No account, no backend | There is nothing to sign in to and nothing to sign in against |
| No analytics involving audio | No analytics or crash-reporting SDK at all — see below |
| No third-party code touches audio | The app has no third-party runtime dependencies beyond AndroidX/Compose |
| Only four permissions | `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`. Notably **no Bluetooth permission and no location permission** — see [ADR-0007](adr/0007-minimal-permission-set.md) |

### Why no analytics or crash-reporting SDK

Standard practice for an ordinary app; a contradiction for this one. A third-party SDK in a
microphone app is a claim the user has to take on faith — they cannot audit what it collects, and
the app's whole pitch is that they do not have to. Shipping without one makes "audio never leaves
the phone" a statement about the *binary*, not about our intentions.

The cost is real: no crash telemetry, so bugs surface only when someone reports them. For an app of
this size that is an acceptable trade. Recorded in
[ADR-0007](adr/0007-minimal-permission-set.md).

## Verifiability

The point of the `INTERNET` decision is that a sceptical user does not have to believe us:

```bash
# Any user, on any build, without our cooperation:
aapt dump permissions app-release.apk
# or
adb shell dumpsys package com.<id>.remoteear | grep -A20 "requested permissions"
```

If `android.permission.INTERNET` is absent, the app cannot upload audio. Not "does not", *cannot*.
No privacy policy achieves that.

**Release-build check (Phase 7):** verify the *merged* release manifest lists exactly the four
expected permissions and nothing else. A dependency can add `INTERNET` through manifest merging
without anyone writing a line of network code — that is precisely the kind of drift this check
exists to catch.

## The microphone should be conspicuous

An app that listens for hours should be obvious about it, not discreet:

- The ongoing notification is required by the platform and welcome regardless. It always states the
  true state — `Monitoring`, or `Paused — headphones disconnected` — never a stale one.
- Android's own microphone indicator will be lit throughout. Good.
- **Monitoring is only ever started by a person tapping START in a visible app.** There is no
  boot receiver, no schedule, no remote trigger. The API 31+ background-start restriction happens to
  enforce this, but it is also the intent.
- **A paused monitor must look paused.** The worst failure mode in this product is a user believing
  they are listening when they are not, which is why `Paused` carries a reason
  ([audio-pipeline.md](audio-pipeline.md)) and why [risk R1](risks.md) is ranked first.

## What the user is told

In-app, on the main screen — plain, short, and true:

> Microphone audio is processed on this phone only and played to your headphones. Nothing is
> recorded, saved, or sent anywhere. RemoteEar has no internet access.

In the permission rationale, before the system dialog appears:

> RemoteEar needs the microphone so you can hear this room through your headphones. Audio is never
> recorded or sent anywhere — it goes straight to your headphones and is gone.

Both sentences must remain literally true. If a future change makes either false, the change is
wrong or the sentence must change first — and the sentence changing is a decision that needs an ADR.

And in full, from the overflow menu, the **privacy policy** and **terms of use**:
[`app/src/main/assets/legal/`](../app/src/main/assets/legal/). The app renders those exact files
rather than a copy in `strings.xml`, so what a sceptical reader audits in this repository is
byte-for-byte what the app displays. See [M16](mvp-scope.md).

## Play Data Safety

The declaration must be accurate rather than flattering:

| Question | Answer |
|---|---|
| Does the app collect or share audio? | **Not collected** in the transferred-off-device sense. Audio is processed ephemerally in memory and never transmitted or stored |
| Is data encrypted in transit? | Not applicable — no data is transmitted |
| Can users request deletion? | Not applicable — nothing is retained |
| Data types collected | None |

Supported by the absence of `INTERNET`. Details, plus the prominent-disclosure and
foreground-service-justification requirements, are in the Play section of
[android-constraints.md](android-constraints.md). Tracked as [risk R3](risks.md).

## Future features that would threaten this

Recorded now, while it is cheap to say:

- **Cry detection** (brief §11) must stay **on-device**. A cloud model would break every invariant
  on this page. It also makes a safety claim the app cannot honour — see
  [mvp-scope.md](mvp-scope.md).
- **Sleep-sound cancellation** ([ADR-0008](adr/0008-sleep-sound-deferred.md)) is pure local DSP and
  raises no privacy question. If a proposed implementation needs a network call or a downloaded
  model, that is a signal to stop.
- **Any bug-reporting mechanism** must be manual and audio-free: the user copies a text log and
  sends it themselves. No automatic upload, ever.
