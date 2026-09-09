# ADR-0005 — A `microphone`-type foreground service hosts monitoring, and stays alive while paused

- **Status:** Accepted
- **Date:** 2026-09-09

## Context

Brief §4 requires monitoring to continue when the app is backgrounded (§4.5) and when the screen is
locked (§4.6), and §8 asks for the current Android requirements for microphone access from a
foreground service.

Since Android 9, background apps cannot access the microphone. A foreground service of type
`microphone` is the mechanism the platform provides, and from API 34 it also requires the
`FOREGROUND_SERVICE_MICROPHONE` permission.

Two platform rules then interact in a way that decides the design:

1. **`RECORD_AUDIO` must be granted before** the service is promoted with
   `FOREGROUND_SERVICE_TYPE_MICROPHONE`, or API 34+ raises a `SecurityException`.
2. **Since API 31, a background app cannot start a foreground service at all.**

Rule 2 has a non-obvious consequence. Brief §16 asks whether monitoring can resume automatically
when the headphones reconnect. If the service **stops** when Bluetooth drops, then — with the app in
the background and the phone in another room — it **cannot start itself again**. Auto-resume would
require the user to walk back, pick up the phone, and reopen the app, which defeats the feature.

## Decision

**`MonitoringService`, `foregroundServiceType="microphone"`, `exported="false"`, started from a
visible Activity — and it owns a `Paused` state rather than stopping when audio cannot flow.**

- The service is the single owner of the pipeline and of `MonitorState`. The UI observes; it does not
  drive.
- Interruptions (Bluetooth loss, focus loss, a call, microphone preemption) **close the audio
  streams but keep the service running**, with the notification stating the reason.
- The service stops only when the user stops it — from the app, or from the notification's `Stop`
  action.

## Consequences

- Automatic resume on Bluetooth reconnect becomes possible at all, which it would not otherwise be.
- One state machine in one place ([audio-pipeline.md](../audio-pipeline.md)), surviving
  configuration changes and process backgrounding without extra machinery.
- The notification is a real status surface, not decoration: once the phone is in another room it is
  the *only* surface, so it carries the same information as the UI in brief §9.
- **A paused service holds a foreground-service slot and a notification while not listening.** This
  is the honest cost of the decision. Mitigated by making paused states unmistakably paused — never
  a notification that says "Monitoring" while the streams are closed. For this product, a user who
  believes they are listening when they are not is the worst possible outcome
  ([risk R1](../risks.md)).
- Permission sequencing is fixed by rule 1: microphone permission, then service, then streams. The
  UI flow in [android-constraints.md](../android-constraints.md) follows from it.
- Monitoring can only ever be started deliberately, by a person, in a visible app. Rule 2 enforces
  it, and it is also what we want — see [privacy.md](../privacy.md).
- Does **not** protect against OEM battery managers, which kill foreground services regardless
  ([risk R1](../risks.md)). No service configuration can. The mitigation is detection and honesty.

## Alternatives considered

**Stop the service on interruption, restart on recovery.** Simpler state model, and impossible: API
31+ forbids the background start that recovery would require. This is the alternative that looks
right until you read the restriction.

**Run the pipeline in the Activity.** Fails brief §4.5 and §4.6 immediately — the microphone is
revoked as soon as the app is no longer visible.

**`WorkManager` or a bound service.** Neither provides an ongoing microphone grant; the typed
foreground service is the platform's answer here.

**Add `RECEIVE_BOOT_COMPLETED` and auto-start.** Rejected on both platform and product grounds:
recent Android versions restrict which service types may start from boot, and a microphone that
turns itself on after a reboot is not a thing this app should be.
