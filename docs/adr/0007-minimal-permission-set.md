# ADR-0007 — Four permissions, and no `INTERNET`

- **Status:** Accepted
- **Date:** 2026-09-09

## Context

Brief §17: *"Request only permissions that are actually required… Do not request location permission
unless Android's current Bluetooth API genuinely requires it."* Brief §12 makes privacy a core
property: *"No audio should leave the phone."*

A privacy promise stated in a policy document asks the user for trust. A privacy promise enforced by
the manifest asks for nothing — it is checkable by anyone with `aapt`.

Two findings from [feasibility Q2](../feasibility.md) make the set unusually small:

- **`AudioManager.getDevices()` exposes device *types* without any Bluetooth permission.** The UI
  needs a status dot, not a name.
  > **Correction, 2026-09-10 — measured on hardware.** This bullet originally continued: *"Only the
  > human-readable product name requires `BLUETOOTH_CONNECT`."* **That is wrong.**
  > `AudioDeviceInfo.getProductName()` returned the earbuds' real name on the OnePlus CPH2399 with
  > none of the Bluetooth permissions held — see
  > [the Phase 5 run](../test-runs/2026-09-10-oneplus-cph2399-phase5.md). The decision below is
  > unchanged and in fact *strengthened*: the nicety that was supposedly the only reason to want
  > `BLUETOOTH_CONNECT` turns out to be free. The app still falls back to "Bluetooth headphones" if
  > a device or OEM declines, because the getter's behaviour without that permission is not
  > documented plainly enough to rely on.
- **[ADR-0004](0004-media-path-only.md) forbids changing global audio state**, which is what
  `MODIFY_AUDIO_SETTINGS` exists for.

## Decision

**Exactly these four:**

| Permission | Runtime? | For |
|---|---|---|
| `RECORD_AUDIO` | Yes | The product |
| `FOREGROUND_SERVICE` | No | Running any foreground service |
| `FOREGROUND_SERVICE_MICROPHONE` | No | Declaring the `microphone` type (API 34+) |
| `POST_NOTIFICATIONS` | Yes (API 33+) | Showing the ongoing notification |

**And explicitly not these:**

| Not requested | Why not |
|---|---|
| `INTERNET` | There is no network code. Its absence makes "audio never leaves the phone" a fact about the binary |
| `BLUETOOTH_CONNECT` | Types suffice — and, measured 2026-09-10, so do *names*: `getProductName()` gives them without it |
| `BLUETOOTH_SCAN`, `BLUETOOTH` | The app never scans or pairs — that is the system's job |
| `ACCESS_FINE_LOCATION` | Coupled to Bluetooth *scanning*, which we do not do. Brief §17's concern does not arise |
| `MODIFY_AUDIO_SETTINGS` | Forbidden by [ADR-0004](0004-media-path-only.md) |
| `WAKE_LOCK` | A playing `AudioTrack` in a foreground service should suffice; add only if measurement proves otherwise, with an ADR |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Play-policy sensitive, user-hostile as a default, and does not reliably defeat OEM killers anyway |
| `RECEIVE_BOOT_COMPLETED` | A microphone that turns itself on after a reboot is not what this app should be |

**Also excluded: analytics and crash-reporting SDKs**, and third-party runtime dependencies
generally (beyond AndroidX/Compose).

## Consequences

- The privacy claim is **verifiable without trusting us**: `aapt dump permissions` on any build
  shows no `INTERNET`, so the app *cannot* upload audio. Not "does not" — cannot. No privacy policy
  achieves that.
- The permission dialogs a user sees are: the microphone, and notifications. Nothing to explain
  away, and brief §3's "unnecessary permissions" line is satisfied trivially.
- **No crash telemetry.** This is a real cost: bugs surface only when someone reports them. Accepted
  because a third-party SDK inside a microphone app is a claim the user cannot audit, and this app's
  entire pitch is that they do not have to. See [privacy.md](../privacy.md).
- **A drift alarm:** if a future change needs `INTERNET` or `MODIFY_AUDIO_SETTINGS`, something has
  gone wrong architecturally. Both are strong signals, not paperwork.
- ~~Bluetooth device *names* cannot be shown.~~ **Withdrawn 2026-09-10:** they can, via
  `AudioDeviceInfo.getProductName()`, with no Bluetooth permission at all. This consequence was a
  cost this ADR did not actually incur. The app names the headphones and falls back to "Bluetooth
  headphones" only if the platform declines.
- A **merged-manifest check belongs in the release process** (Phase 7): a transitive dependency can
  add `INTERNET` through manifest merging with nobody writing a line of network code. The promise
  needs a test, not just an intention.

## Alternatives considered

**Add `BLUETOOTH_CONNECT` to show device names.** Nicer status text for a runtime permission prompt
on a privacy-sensitive app. Rejected — and the trade turned out not to exist at all: the name is
available without it (see the correction above), so the app shows "Buds Pro" and asks for nothing.

**Add `INTERNET` "just in case" for a future feature.** This is how the promise erodes. Any feature
needing the network needs its own ADR, and the burden of argument should be high.

**Add a crash reporter.** Normal engineering practice, and a contradiction here. If crash data
becomes genuinely necessary, the acceptable form is a manual, audio-free, user-initiated copy-paste
— never an automatic upload.
