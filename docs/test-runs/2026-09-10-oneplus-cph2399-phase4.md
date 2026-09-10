# Test run — 2026-09-10 — OnePlus CPH2399 — Phase 4 UI

| | |
|---|---|
| Device | OnePlus CPH2399 (`OP557AL1`), ColorOS, **Android 14 / API 34** |
| Screen | 1080 × 2400, density 480 (≈ 411 × 915 dp), font scale 0.9 |
| Headphones | Bluetooth A2DP (model not recorded — *still* worth capturing) |
| Build | `df83234` + the permission-settings route, debug |
| Purpose | Does the designed UI render correctly on hardware? |

## Result

**Pass, for what was looked at.** The user unlocked the phone and confirmed the screens render as
designed. Two defects were found by using it, both fixed in this session — see below.

## Verified

| # | Check | Result | How |
|---|---|---|---|
| 1 | Idle screen matches the design | **pass** | screenshot; hollow ring, 30 sp title, Listen pill, status panel, 7-segment volume, noise slider, footnote |
| 2 | Listening screen matches the design | **pass** | screenshot; double ring + filled centre, teal 34 sp "Listening", Stop as a rounded square with a square glyph |
| 3 | Nothing clipped or scrolling at 411 × 915 dp | **pass** | after the diagnostics fix below |
| 4 | Microphone row reads "In use by RemoteEar" while listening | **pass** | screenshot |
| 5 | Routing still `BUILTIN_MIC` → `BLUETOOTH_A2DP` under the new UI | **pass** | `dumpsys activity services` shows `types=00000080`, foreground, no SCO |
| 6 | Launch does not flash white | **pass** | dark launch theme; observed |
| 7 | Overflow menu, privacy policy, terms, about | **pass** (user) | "looks good" — not screenshotted, the phone re-locked during capture |
| 8 | No crash across install, launch, listen, stop | **pass** | logcat clean of `AndroidRuntime` |

## Defects found by using it, and fixed

1. **`BLUETOOTH_A2DP` in the headphones row** — a debug string in user-facing copy. Now
   `AudioDeviceInfo.getProductName()` with a "Bluetooth headphones" fallback.
2. **The listening state text sat in a scrolling box.** Cause was the on-screen diagnostics block
   consuming height, *not* the supporting paragraph — the paragraph was removed first, wrongly, and
   restored once the real cause was found. Diagnostics is now behind a long-press on the wordmark.

## Not tested — and these are gaps, not passes

| # | Check | Why not |
|---|---|---|
| A | **All four `Paused` reasons on the device** | Nothing triggers them yet: Phase 5 wires the triggers. Verified only as previews |
| B | **Both `Stopped` kinds on the device** | Same — no way to provoke a wrong route or a failed open on demand |
| C | **The dim state after 20 s** | Not observed. The screen timeout fires first on this phone's settings |
| D | **Light theme on the device** | Phone is in dark mode; verified only as previews |
| E | **Whether `getProductName()` yields a real name without `BLUETOOTH_CONNECT`** | Open. The docs do not say plainly, the fallback hides the difference, and nobody has read the row since the change. **If it shows "Bluetooth headphones" rather than the earbuds' name, that is the answer** |
| F | **Volume by ear** — phone buttons and earbud controls while listening | An earlier `adb` attempt produced a false negative (monitoring was not running, so the keys moved the ringer) |
| G | **Quiet room with noise reduction high** ([R2](../risks.md)) | Needs a genuinely quiet room, not an office |
| H | **Numeric latency** | No clap test yet. The budget is 180–400 ms and remains unmeasured |
| I | **Talk-into-the-earbud check** | Passed in Phase 2 and not re-run; the routing assertion is what guards it now |
| J | **Permanent permission denial → "Open settings"** | Implemented after the device was locked again. Untested; needs two refusals to reach |
| K | Multi-hour endurance, drift, battery, OEM process kill | Phase 6 / Scenario G |
| L | LE Audio (H2), a near-AOSP OEM baseline, Android 15/16 behaviour | No hardware; not testable on API 34 |

## Notes

- **The Material 3 slider no longer looks like the design.** The current M3 slider draws the thumb as
  a tall vertical bar with a separate stop-indicator dot at the track end; the design specified a
  circular thumb on a continuous track. It reads correctly — "Off" is unambiguous — but it is a
  visible divergence, left as-is pending a decision.
- Reinstalling the APK kills the service and so ends a listening session. Worth remembering before
  reinstalling during a test someone is relying on.
