# ADR-0002 — `minSdk 29`, `targetSdk`/`compileSdk 36`

- **Status:** Accepted
- **Date:** 2026-09-09

## Context

Brief §15: *"Before choosing the minimum SDK, investigate which APIs are required for microphone
foreground service, Bluetooth audio routing, modern Android permissions, and audio device
selection. Prefer broad compatibility without introducing legacy complexity."*

The binding API levels ([feasibility Q7](../feasibility.md)):

| API | Since |
|---|---|
| `AudioDeviceCallback`, `AudioDeviceInfo` | 23 |
| `setPreferredDevice()`, `AudioSource.UNPROCESSED` | 24 |
| `AudioFocusRequest` | 26 |
| **`foregroundServiceType`** | **29** |
| `AudioDeviceInfo.TYPE_BLE_HEADSET` | 31 |
| `POST_NOTIFICATIONS` | 33 |
| `FOREGROUND_SERVICE_MICROPHONE` | 34 |

API 29 is where the architecture becomes expressible: it is the first level with typed foreground
services, which is the mechanism for holding the microphone in the background. Below it, the
background-microphone story is materially different — which is precisely the legacy complexity the
brief asks to avoid.

## Decision

**`minSdk = 29` (Android 10). `compileSdk = targetSdk = 36`.**

Everything above 29 degrades into two small version guards rather than alternative architectures:

- `TYPE_BLE_HEADSET` (31) — checked only when adding it to the set of acceptable output types.
- `POST_NOTIFICATIONS` (33) — requested only on 33+.

`FOREGROUND_SERVICE_MICROPHONE` needs no guard: declaring a permission the platform does not yet
know about is harmless.

## Consequences

- Roughly 97% of active Android devices are reachable — broad compatibility, as asked.
- No compatibility shims in the audio layer. Every API the pipeline depends on is unconditionally
  available.
- Android 9 and below are excluded. Accepted: supporting them would mean a second background
  strategy for a small and shrinking share of devices.
- `targetSdk 36` means the newest platform restrictions apply immediately rather than being deferred
  by a lower target. That is deliberate — a monitor must behave correctly under current rules, not
  survive on grandfathered ones. It also makes [risk R10](../risks.md) real, which is why Phase 1 of
  the [implementation plan](../implementation-plan.md) re-verifies the foreground-service rules
  before any service code is written.
- Testing must include a real Android 10 device, not just an emulator — see
  [test-matrix.md](../test-matrix.md).

## Alternatives considered

**`minSdk 31` (Android 12).** Tempting: `TYPE_BLE_HEADSET` and the modern Bluetooth permission model
become unconditional, so the audio layer would have zero version guards. Rejected — it drops
Android 10 and 11 users to remove two `if` statements.

**`minSdk 26` or lower.** Maximum reach, but no `foregroundServiceType`, and a different
background-microphone model. This is the legacy complexity brief §15 explicitly warns against, for a
diminishing device share.
