# Android platform constraints

> Deliverable 4 of the project brief (§21): permissions, foreground-service requirements,
> Bluetooth/audio-routing limitations, and background-execution constraints.
>
> Targets `minSdk 29` / `targetSdk 36` ([ADR-0002](adr/0002-min-and-target-sdk.md)).

## Verification status

**Verified against `developer.android.com` on 2026-09-09** (Phase 1 of the
[implementation plan](implementation-plan.md)). The four questions this section previously carried
are answered below; sources are listed at the end of this document.

| Question | Answer |
|---|---|
| Is the `microphone` FGS type subject to a timeout? | **No.** Timeouts apply to `dataSync` and `mediaProcessing` (6 h per 24 h, Android 15+) and `shortService`. `microphone` is not among them |
| Current background-start exemptions | Listed below — but a **second, stricter layer** applies to microphone services. See *while-in-use restrictions* |
| Is `FOREGROUND_SERVICE_MICROPHONE` still correct? | **Yes.** Required from Android 14 (API 34), with `RECORD_AUDIO` as a runtime prerequisite |
| New microphone indicator or revocation behaviour? | No new indicator. But the platform **delivers silence rather than an error** when capture is denied or lost — this changed the design, see *microphone silencing* |

Two findings altered decisions rather than merely confirming them: the **while-in-use restriction**
and **silence-instead-of-error**. Both are marked below. One more — the Android 15 audio-focus
requirement — adds an ordering constraint.

Android 16 (API 36) introduces **no** behaviour changes affecting foreground services, microphone
access, audio focus, or the audio APIs. Its one relevant change is Bluetooth bond-loss handling
(see below).

## Permissions

The complete set. Every entry has to justify itself; see
[ADR-0007](adr/0007-minimal-permission-set.md).

| Permission | From | Runtime? | Why RemoteEar needs it |
|---|---|---|---|
| `RECORD_AUDIO` | 1 | **Yes** | The product. Must be granted *before* the foreground service is promoted |
| `FOREGROUND_SERVICE` | 28 | No | Normal permission; required to run any foreground service |
| `FOREGROUND_SERVICE_MICROPHONE` | 34 | No | Declares the `microphone` service type. Harmless on lower levels |
| `POST_NOTIFICATIONS` | 33 | **Yes** | Shows the ongoing monitoring notification. Guarded by an API check |

### Permissions deliberately **not** requested

| Not requested | Why not |
|---|---|
| `INTERNET` | There is no network code. Its absence turns "audio never leaves the phone" from a promise into a manifest-level fact anyone can verify |
| `BLUETOOTH_CONNECT` | Only needed to read a Bluetooth device's *name*. `AudioManager.getDevices()` exposes the device *type* without it, and a status dot needs the type, not the name |
| `BLUETOOTH_SCAN`, `BLUETOOTH` | The app never scans, pairs, or manages Bluetooth. Pairing is the system's job |
| `ACCESS_FINE_LOCATION` | Historically coupled to Bluetooth *scanning*. We do not scan, so brief §17's concern does not arise |
| `WAKE_LOCK` | An actively playing `AudioTrack` inside a foreground service should be sufficient. Add only if long-run testing proves otherwise — and record an ADR if it does |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Play-policy sensitive and user-hostile as a default. Guide the user to their own battery settings instead |
| `RECEIVE_BOOT_COMPLETED` | Monitoring is always started deliberately by a person. Auto-start would be both creepy and blocked |
| `MODIFY_AUDIO_SETTINGS` | Only needed to change *global* audio state, which [ADR-0004](adr/0004-media-path-only.md) forbids |

That last row is worth restating: needing `MODIFY_AUDIO_SETTINGS` would be a **signal that something
has gone wrong**. It is the permission the comms-path APIs want. If a future change starts asking
for it, the change is probably violating ADR-0004.

### Permission sequencing

`RECORD_AUDIO` must be held before `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`, so
the order is fixed:

```text
user taps START
   -> RECORD_AUDIO granted?           no  -> rationale, then request; stop here
   -> POST_NOTIFICATIONS (API 33+)?   no  -> request; continue either way
   -> usable Bluetooth sink present?  no  -> explain, do not start
   -> startForegroundService()
   -> startForeground(type = microphone)
   -> open streams, assert routing
```

`POST_NOTIFICATIONS` is a soft dependency: denying it does not prevent the foreground service from
running, it just hides the notification, which is bad UX for a continuously-listening app. Explain
the consequence rather than blocking.

## Foreground service

```xml
<service
    android:name=".monitor.MonitoringService"
    android:exported="false"
    android:foregroundServiceType="microphone" />
```

| Rule | Consequence of getting it wrong |
|---|---|
| `RECORD_AUDIO` granted before promotion | `SecurityException` on API 34+ |
| Started from a **visible** Activity (API 31+) | `ForegroundServiceStartNotAllowedException` |
| Not started from a `BOOT_COMPLETED` receiver | `ForegroundServiceStartNotAllowedException`. Prohibited for `microphone` since Android 14 |
| `startForeground()` called within a few seconds of `startForegroundService()` | ANR-class crash |
| Manifest type matches the `startForeground()` type argument | `IllegalArgumentException` / `SecurityException` |
| Service is `exported="false"` | Any app could start your microphone |
| No timeout applies | — `microphone` is not a timed type, so a multi-hour session is permitted |

### Two layers of background-start restriction

This is the finding that most changed the picture: **there are two separate restrictions, and the
microphone type is subject to both.**

**Layer 1 — the general FGS background-start ban (Android 12+).** Broad, with a long exemption list:
transitioning from a visible activity, high-priority FCM, user interaction with a notification or
widget, exact alarms, boot and time-change broadcasts, the active input method, geofencing
transitions, Companion Device Manager, a user-granted battery-optimisation exemption, and
`SYSTEM_ALERT_WINDOW`. Violation raises `ForegroundServiceStartNotAllowedException`.

**Layer 2 — while-in-use restrictions (Android 14+).** *Narrower exemptions, and it is the one that
binds us.* Because `RECORD_AUDIO` is a while-in-use permission, the platform evaluates it **at the
moment the service is created**. Starting a `microphone` foreground service from the background
raises a **`SecurityException`** — even though `checkSelfPermission()` reports
`PERMISSION_GRANTED`. The same applies to `camera` and `location` services.

Its exemptions are a much shorter list: a system component starts the service; the service is
started from an **app widget** or a **notification**; a `PendingIntent` sent by a different, visible
app; a device-owner policy controller; a `VoiceInteractionService`.

**The consequence for RemoteEar is decisive, and it is good news for the design:** the restriction
**applies only to *starting* a service, not to one already running.** A service that stays alive in
a `Paused` state can reopen its audio streams freely — no new start, so no restriction. A service
that had stopped could not come back at all. This is exactly the argument in
[ADR-0005](adr/0005-foreground-service-hosts-monitoring.md), and it turns out to rest on a stronger
rule than the one it was originally written against.

Worth noting for S1 (auto-resume): "started from a notification" is a while-in-use exemption, so a
`Resume` action on our own notification is a legitimate way to restart a *stopped* service with
microphone access. Useful as a fallback, but the paused-service design is better — it needs no user
tap at all.

**Diagnostic.** When this restriction bites, logcat carries:

```text
Foreground service started from background can not have
location/camera/microphone access: service SERVICE_NAME
```

### Notification

- **Low-importance channel**, no sound, no vibration. It must be visible, not annoying.
- **Ongoing** (`setOngoing(true)`), not dismissible while monitoring.
- Shows the live state: `Monitoring`, or `Paused — headphones disconnected`, and so on. The
  notification is the *only* status surface once the phone is in another room, so it should carry the
  same information as the UI in brief §9.
- A **`Stop`** action, so the user never has to reopen the app to end monitoring.
- Not merely a platform obligation: an app holding the microphone for hours should be conspicuous.
  This is a privacy feature. See [privacy.md](privacy.md).

### The service must outlive the audio streams

Because API 31+ forbids starting a foreground service from the background, a service that stops when
the headphones disconnect **cannot restart itself** when they come back. Auto-resume would then
require the user to reopen the app — defeating the point.

Therefore the service owns a `Paused` state and keeps running while paused, with the streams closed
and the notification explaining why. Recorded as
[ADR-0005](adr/0005-foreground-service-hosts-monitoring.md); the state machine is in
[audio-pipeline.md](audio-pipeline.md).

An honest consequence: a paused service still holds a foreground-service slot and a notification for
something that is not currently listening. That is the right trade for a monitor — but it must be
*visibly* paused, never quietly.

## Background execution

| Mechanism | Effect here |
|---|---|
| Screen off | Not Doze. No effect on a running foreground service |
| Doze | Needs stationary + unplugged + idle for a sustained period. Restricts network, jobs, and alarms — none of which this app uses. A foreground service is largely exempt |
| App Standby buckets | Concern background work scheduling; a running foreground service is not affected |
| Background microphone restriction (Android 9+) | Exactly what the `microphone` service type exists to satisfy |
| Background FGS start ban (Android 12+) | Shapes the design — see above |
| **OEM battery managers** | **The actual risk.** Outside AOSP rules and outside our control |

That last row is [risk R1](risks.md), the highest-ranked risk in the project. Xiaomi, Huawei,
Samsung, Oppo, Vivo and OnePlus all kill background processes more eagerly than AOSP specifies, and
no manifest entry compels them not to. The mitigation is not technical but honest: detect that a
monitoring session ended without the user stopping it, and say so afterwards.

## Microphone silencing and capture priority

**The platform delivers silence, not an error.** When capture is denied or lost, `AudioRecord`
keeps returning buffers full of zeros. There is no exception and no callback failure. For a monitor
this is the most dangerous failure shape possible: the app looks like it is working and the user
hears nothing unusual, because a quiet room also sounds like nothing.

This happens in two situations:

1. **The user disables the microphone** with the system privacy toggle. Apps receive silence.
2. **Another app wins the microphone.** Since Android 10 a concurrent-capture policy decides who
   gets audio; the loser is silenced.

**Detect it with the platform API, not a heuristic.** `AudioRecord.registerAudioRecordingCallback()`
— which must be registered **before** capture starts — delivers an `AudioRecordingConfiguration`
whose **`isClientSilenced()`** reports exactly this condition. Available from API 29, which is our
`minSdk` floor, so no version guard is needed. A zero-frame heuristic remains a reasonable
belt-and-braces backstop, but it is the fallback, not the mechanism.

### Priority ordering

Documented rules, in order:

1. Privileged apps outrank ordinary apps.
2. Apps with a visible UI **or a foreground service** outrank background apps.
3. Apps capturing from a **privacy-sensitive** source outrank those that are not.
4. **Two ordinary apps can never capture at the same time.**
5. A privileged app can sometimes share input with another app.
6. Between two background apps of equal priority, the most recently started wins.

Only `CAMCORDER` and `VOICE_COMMUNICATION` are privacy-sensitive.

**`AudioSource.MIC` is not privacy-sensitive — so RemoteEar structurally loses the microphone to any
VoIP app**, and the documentation is explicit that the privacy-sensitive app wins "even if [the
other] has a UI on top or started capturing more recently". Rule 2 is what our foreground service
buys us: it lifts us to foreground-equivalent priority against other *ordinary* apps, which is the
common case. Rule 3 is the ceiling we cannot raise without violating
[ADR-0004](adr/0004-media-path-only.md).

That ceiling is acceptable, and it happens to align with intent: when a call starts we want to pause
anyway ([risk R7](risks.md)). But it must be *observed and reported*, not assumed — hence
`isClientSilenced()`.

## Audio focus requires the foreground service (Android 15+)

Apps targeting Android 15 (API 35) or higher **must be the top app or be running a foreground
service** in order to request audio focus. Otherwise `requestAudioFocus()` returns
`AUDIOFOCUS_REQUEST_FAILED`.

This imposes an **ordering constraint**: request audio focus from inside the running foreground
service, *after* `startForeground()` succeeds — never from the Activity beforehand, and never
speculatively. Resuming from `Paused` is safe, because the service is still running and therefore
still qualifies. One more reason the service outlives the streams.

## Bluetooth and audio routing

The full explanation is in [feasibility Q1/Q3](feasibility.md) and the rule is
[ADR-0004](adr/0004-media-path-only.md). The constraints in short:

| Constraint | Detail |
|---|---|
| A2DP is output-only | It cannot supply a microphone, so it never competes for one |
| HFP/SCO is bidirectional and narrowband | Entering it hands input to the headset mic — forbidden |
| Profile switching is triggered by *mode*, not by recording | See the trigger table in feasibility Q3 |
| Routing preferences are requests | `setPreferredDevice()` may be overridden; assert `getRoutedDevice()` afterwards |
| LE Audio is bidirectional by design | *(unverified)* may engage the earbud mic on an active capture stream — [risk R4](risks.md) |
| Absolute volume varies by earbud | `setVolume()` may be coarse or near-inert at low settings |
| Codec determines most of the latency | 100–250 ms lives in the earbud, not in our code |

### Bluetooth bond loss (Android 16)

The one Android 16 change relevant to this project. When a previously bonded device fails
authentication on reconnect, the stack now disconnects the link, keeps local bond information, and
shows a system dialog asking the user to re-pair. Apps can receive **`ACTION_KEY_MISSING`** to detect
remote bond loss and give better feedback.

**Do not depend on it.** The documentation states plainly that broadcasting these intents varies by
OEM; where `ACTION_KEY_MISSING` is not broadcast, the ACL link stays connected and the system removes
the bond — the Android 15 behaviour. So this is at best a nicer error message layered on top of our
real mechanism, which stays `AudioDeviceCallback`: it reports what the *audio system* decided, needs
no Bluetooth permission, and behaves the same on every version.

### One media route at a time

Relevant to the sleep-sound feature in brief §23: while a Bluetooth sink is connected, Android routes
media output to it. Playing white noise from the **phone speaker** while monitoring streams to the
**earbud** therefore requires two simultaneous, differently-routed output streams. The only
sanctioned lever is `setPreferredDevice(TYPE_BUILTIN_SPEAKER)` on a second `AudioTrack`, and whether
the audio policy honours it is device-dependent.

This is upstream of the echo-cancellation problem the brief anticipates: if concurrent dual routing
does not work, there is nothing to cancel. See [ADR-0008](adr/0008-sleep-sound-deferred.md).

## Google Play considerations

Not a platform API constraint, but a real constraint on shipping — and one the brief does not
mention. A continuously-listening app attracts scrutiny:

- **Data Safety declaration.** Microphone audio is collected in the ordinary sense of "processed",
  but never transmitted or stored. The declaration must say exactly that; the absence of `INTERNET`
  supports it.
- **Prominent disclosure.** The app must explain microphone use in context, before requesting it —
  which the permission rationale does anyway.
- **Foreground-service type justification.** Play requires a declared reason for microphone-type
  foreground services. "Continuously plays the device microphone to the user's own headphones; the
  service exists so monitoring survives the screen locking" is straightforward, but must be written.
- **Sensitive-category framing.** "Baby monitor" is a safety-adjacent claim. The store listing should
  describe what the app does — plays a microphone to headphones — and avoid implying it will alert
  anyone to anything.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` would add policy risk for little gain. Not requested.

Tracked as [risk R3](risks.md) and Phase 7 of the [implementation plan](implementation-plan.md).

## Sources

Verified 2026-09-09 against:

- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) — `microphone` type, permission, prerequisites
- [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) — which types are timed
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) — both restriction layers and their exemptions
- [Behavior changes: apps targeting Android 15](https://developer.android.com/about/versions/15/behavior-changes-15) — `BOOT_COMPLETED` restrictions, FGS timeouts, audio-focus requirement
- [Behavior changes: apps targeting Android 16](https://developer.android.com/about/versions/16/behavior-changes-16) and [all apps](https://developer.android.com/about/versions/16/behavior-changes-all) — no audio/FGS changes; Bluetooth bond loss
- [Sharing audio input](https://developer.android.com/guide/topics/media/sharing-audio-input) — capture priority, privacy-sensitive sources, silencing
- [`AudioRecordingConfiguration`](https://developer.android.com/reference/android/media/AudioRecordingConfiguration) — `isClientSilenced()`

Re-verify when raising `targetSdk` beyond 36.
