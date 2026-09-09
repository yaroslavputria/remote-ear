# Foreground service rules

RemoteEar's monitoring lives in a `microphone`-type foreground service. Everything here follows from
two platform rules that interact in a non-obvious way.

Authoritative: [ADR-0005](../../../../docs/adr/0005-foreground-service-hosts-monitoring.md) and
[docs/android-constraints.md](../../../../docs/android-constraints.md).

> **Verified 2026-09-09** against current platform documentation for `targetSdk 36`. The
> `microphone` type has **no timeout**, `FOREGROUND_SERVICE_MICROPHONE` is correct, and Android 16
> changes nothing here. Sources are listed in
> [docs/android-constraints.md](../../../../docs/android-constraints.md). **Re-verify when raising
> `targetSdk` beyond 36** — this area of the platform moves faster than any other.

## Manifest

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

Four permissions, and no more, without an ADR
([ADR-0007](../../../../docs/adr/0007-minimal-permission-set.md)). No `INTERNET`, no Bluetooth
permission, no location, no `WAKE_LOCK`, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## The rules, in the order they bite

| Rule | Failure if broken |
|---|---|
| `RECORD_AUDIO` granted **before** promoting with `FOREGROUND_SERVICE_TYPE_MICROPHONE` | `SecurityException` on API 34+ |
| Started from a **visible** Activity | `ForegroundServiceStartNotAllowedException` (API 31+) **or `SecurityException`** (API 34+, see below) |
| Never started from a `BOOT_COMPLETED` receiver | `ForegroundServiceStartNotAllowedException`. Prohibited for `microphone` since Android 14 |
| `startForeground()` within a few seconds of `startForegroundService()` | ANR-class crash |
| Manifest type matches the `startForeground()` type argument | `IllegalArgumentException` / `SecurityException` |
| `exported="false"` | Any app could start the user's microphone |

**No timeout applies.** Timeouts cover `dataSync` and `mediaProcessing` (6 h per 24 h, Android 15+)
and `shortService`. `microphone` is not a timed type, so multi-hour monitoring is permitted — do not
build a watchdog against a timeout that does not exist.

### There are two background-start restrictions, not one

Getting this wrong produces the wrong exception and the wrong fix.

**Layer 1 — general FGS background-start ban (Android 12+).** Long exemption list (visible-activity
transition, high-priority FCM, notification or widget interaction, exact alarms, boot broadcasts,
input method, geofencing, Companion Device Manager, battery-optimisation exemption,
`SYSTEM_ALERT_WINDOW`). Raises `ForegroundServiceStartNotAllowedException`.

**Layer 2 — while-in-use restrictions (Android 14+). This is the one that binds a microphone
service.** `RECORD_AUDIO` is a while-in-use permission, so the platform re-evaluates it *when the
service is created*. Starting from the background raises a **`SecurityException`** — even though
`checkSelfPermission()` returns `PERMISSION_GRANTED`, which makes it a confusing bug to diagnose.
Exemptions are far shorter: a system component; started from an **app widget** or a **notification**;
a `PendingIntent` from a different visible app; a device owner; a `VoiceInteractionService`.

**It applies only to *starting* a service — not to one already running.** That is precisely why the
service stays alive while paused (below): a surviving service reopens its streams freely; a stopped
one cannot come back.

Logcat, when it bites:

```text
Foreground service started from background can not have
location/camera/microphone access: service SERVICE_NAME
```

### Audio focus needs the service (Android 15+)

Targeting Android 15+, an app must be the **top app or running a foreground service** to obtain
audio focus; otherwise `requestAudioFocus()` merely returns `AUDIOFOCUS_REQUEST_FAILED`.

**Request focus from inside the running service, after `startForeground()` succeeds.** Never
speculatively from the Activity — that is an ordering bug that presents as an unexplained silent
failure. Resuming from `Paused` is safe: the service is still running, so it still qualifies.

## Start sequence

```text
user taps START  (app is visible — this matters)
  |
  +- RECORD_AUDIO granted?         no -> show rationale, request, stop here
  +- POST_NOTIFICATIONS (33+)?     no -> request; continue either way, explain the consequence
  +- usable Bluetooth sink?         no -> explain, do not start
  |
  +- startForegroundService()
  +- startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
  +- open streams, assert getRoutedDevice()
```

`POST_NOTIFICATIONS` is a **soft** dependency: denying it does not stop the service, it hides the
notification — bad UX for a continuously-listening app, but not fatal. Explain rather than block.

`RECORD_AUDIO` is a **hard** dependency, both for the product and for rule 1.

## The service must outlive the audio streams

**This is the design consequence people miss.**

API 31+ forbids starting a foreground service from the background. So a service that *stops* when
the headphones disconnect **cannot restart itself** when they reconnect — the app is backgrounded
and the phone is in another room. Auto-resume would require walking back and reopening the app,
which defeats the feature.

Therefore:

- Interruptions **close the audio streams but keep the service running**, in `Paused(reason)`.
- The service stops **only** when the user stops it — in the app, or via the notification action.

The honest cost: a paused service holds a foreground-service slot and a notification while not
listening. That is the right trade for a monitor, but only if paused states are unmistakable.

## Notification

- **Low-importance channel.** No sound, no vibration. Visible, not annoying.
- **Ongoing** (`setOngoing(true)`) while monitoring.
- **Live state text**, matching the UI: `Monitoring`, `Paused — headphones disconnected`, and so on.
  Once the phone is in another room this is the *only* status surface.
- **A `Stop` action**, so ending a session never requires reopening the app.
- **Never stale.** A notification saying "Monitoring" while the streams are closed is the worst bug
  this product can ship.

It is also a privacy feature: an app holding the microphone for hours should be conspicuous. See
[docs/privacy.md](../../../../docs/privacy.md).

## Background execution

| Mechanism | Effect |
|---|---|
| Screen off | **Not Doze.** No effect on a running foreground service |
| Doze | Needs stationary + unplugged + idle for a sustained period; restricts network/jobs/alarms, none of which this app uses. Foreground services are largely exempt |
| App Standby buckets | Concern scheduling of background work; irrelevant to a running FGS |
| **OEM battery managers** | **The real risk** — outside AOSP rules and outside our control |

Do **not** add `WAKE_LOCK` speculatively. A playing `AudioTrack` inside a foreground service should
be sufficient; add it only if long-run testing shows stalls, and then write an ADR, because it is a
new permission and therefore a decision.

Do **not** request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Play-policy sensitive, user-hostile as a
default, and it does not reliably defeat OEM killers anyway.

## OEM process kills

Xiaomi, Huawei, Samsung, Oppo, Vivo and OnePlus all kill background processes more eagerly than AOSP
specifies. No manifest entry compels them not to.
[Risk R1](../../../../docs/risks.md) is ranked highest in the project for a reason: the user believes
they are monitoring their child and they are not.

The mitigation is honesty, not cleverness:

- Detect a session that ended without the user stopping it, and say so on next open.
- Point the user at their own battery settings **after** observing a kill.
- If a device kills reliably, document it. "Does not work on X" beats a monitor that stops silently.

## Do not add

- **`RECEIVE_BOOT_COMPLETED` / auto-start.** Recent Android restricts which service types may start
  from boot, and a microphone that turns itself on after a reboot is not what this app should be.
- **A second service.** One service owns the pipeline and the state. The UI observes; it does not
  drive.
