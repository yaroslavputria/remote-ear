# Android platform constraints

> Deliverable 4 of the project brief (§21): permissions, foreground-service requirements,
> Bluetooth/audio-routing limitations, and background-execution constraints.
>
> Targets `minSdk 29` / `targetSdk 36` ([ADR-0002](adr/0002-min-and-target-sdk.md)).

## Open verification task

**Before Phase 2 code is written, re-check this document against the current
`developer.android.com`.** It was written from knowledge with a cutoff, and foreground-service rules
are among the fastest-moving parts of the platform: recent Android releases have added
foreground-service *timeouts* for certain types, tightened background starts, and restricted which
types may be started from `BOOT_COMPLETED`. Specifically confirm, for `targetSdk 36`:

1. whether the `microphone` foreground-service type is subject to any timeout,
2. the current exemption list for starting a foreground service from the background,
3. whether `FOREGROUND_SERVICE_MICROPHONE` remains the correct permission name and requirement,
4. any new user-facing microphone indicators or revocation behaviour.

This is Phase 1 of the [implementation plan](implementation-plan.md), and it is a *documentation*
task with a documentation deliverable — amend this file, do not just read and move on.

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
| `startForeground()` called within a few seconds of `startForegroundService()` | ANR-class crash |
| Manifest type matches the `startForeground()` type argument | `IllegalArgumentException` / `SecurityException` |
| Service is `exported="false"` | Any app could start your microphone |

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
