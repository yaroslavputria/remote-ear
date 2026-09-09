# Foreground service rules

RemoteEar's monitoring lives in a `microphone`-type foreground service. Everything here follows from
two platform rules that interact in a non-obvious way.

Authoritative: [ADR-0005](../../../../docs/adr/0005-foreground-service-hosts-monitoring.md) and
[docs/android-constraints.md](../../../../docs/android-constraints.md).

> **Verify before writing service code.** Foreground-service rules move faster than any other part
> of the platform — recent releases have added service-type timeouts, tightened background starts,
> and restricted boot-time starts. Phase 1 of the implementation plan exists to re-check this against
> current `developer.android.com` for `targetSdk 36`. Treat the specifics below as
> *(needs re-verification)* rather than settled.

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
| Started from a **visible** Activity (API 31+) | `ForegroundServiceStartNotAllowedException` |
| `startForeground()` within a few seconds of `startForegroundService()` | ANR-class crash |
| Manifest type matches the `startForeground()` type argument | `IllegalArgumentException` / `SecurityException` |
| `exported="false"` | Any app could start the user's microphone |

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
