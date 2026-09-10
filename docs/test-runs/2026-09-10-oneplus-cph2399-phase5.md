# Test run — 2026-09-10 — OnePlus CPH2399 — Phase 5 interruptions

| | |
|---|---|
| Device | OnePlus CPH2399 (`OP557AL1`), ColorOS, **Android 14 / API 34** |
| Headphones | Bluetooth A2DP; **the app now shows the earbuds' own name** (see finding 1) |
| Build | Phase 5, debug |
| Scenario | **E — Bluetooth disconnect and reconnect** ([test-matrix.md](../test-matrix.md)) |
| Method | `adb shell svc bluetooth disable` / `enable`, with `adb logcat -s RemoteEar:V` streamed to a file so nothing rotated out |

## Scenario E — pass, twice

Run twice, ten seconds apart, with identical results.

```text
14:59:16  state -> Paused(reason=BluetoothGone)          <- Bluetooth off
14:59:24  attempting to resume from Paused(BluetoothGone) <- Bluetooth back on
14:59:24  state -> Starting
14:59:24  state -> Paused(reason=BluetoothGone)           <- sink present but not ready
14:59:25  attempting to resume from Paused(BluetoothGone)
14:59:25  state -> Starting
14:59:26  state -> Monitoring(inputSource=Mic, routedInType=15, routedOutType=8)

15:00:32  state -> Paused(reason=BluetoothGone)
15:00:39  attempting to resume ... 15:00:39 Paused again ... 15:00:41 Monitoring
```

| Check | Result |
|---|---|
| Pause is immediate on disconnect | **pass** — the `AudioDeviceCallback` fires, not the 1 s watcher |
| The screen says what happened | **pass** — amber badge, "Paused", *"Bluetooth headphones disconnected."*, headphones row amber and reading `Disconnected`, volume block correctly gone |
| The notification says what happened | **pass** — title `Paused — headphones disconnected`, text *"You are not hearing the room. Reconnect your headphones to continue."* Verbatim from [design-spec.md](../design-spec.md), reason in the title as specified |
| Auto-resume on reconnect ([S1](../mvp-scope.md)) | **pass** — ~5 s from Bluetooth on to audio flowing, unattended |
| Notification after resume | **pass** — `Listening` / *"The room is playing to your headphones."* |
| **Routing survives an automatic resume** | **pass** — `routedInType=15` (`BUILTIN_MIC`), `routedOutType=8` (`BLUETOOTH_A2DP`). **No SCO.** The failure that would matter most here did not happen |
| Service stayed alive throughout | **pass** — `isForeground=true types=00000080` across both cycles |

**The retry backoff earned its place on the first real test.** Both cycles show the same shape: the
first resume attempt fires ~3 s after Bluetooth comes back, *fails*, and the second succeeds a
second later. Android reports the A2DP sink as present a moment before it will accept a stream. A
single-shot resume would have left the user paused indefinitely with headphones that looked
connected — the worst kind of failure this product has.

## Findings

1. **`AudioDeviceInfo.getProductName()` returns the real device name without `BLUETOOTH_CONNECT`**
   *(confirmed by the user on screen, 2026-09-10)*. This settles the open question in
   [ADR-0007](../adr/0007-minimal-permission-set.md): naming the headphones costs no permission, and
   the "Bluetooth headphones" fallback is a safety net rather than the normal case.
2. **Latency is "good enough" by ear** *(user judgement, 2026-09-10)*. Not a number: the clap test
   has still never been run, so the 180–400 ms budget in
   [audio-pipeline.md](../audio-pipeline.md) remains unmeasured. Recorded as an opinion, which is
   what it is.

## Not run — and these are gaps, not passes

| Scenario | Why not |
|---|---|
| **F — another app takes the microphone** | Every way of doing this from `adb` means starting a recorder, which writes a file of the user's room to their phone. That is their decision to make, not mine. **The code path is unexercised**, and it is the most dangerous one: Android delivers zeros rather than an error |
| **Phone call** | Cannot place a real call from `adb` responsibly. The reason-naming logic (`getMode()` reading as a call) is therefore **unverified** — if it is wrong, the pause still happens but is labelled *"Another app is playing sound"* instead of *"A phone call is in progress"* |
| **Another app takes audio focus** (music) | Needs a second app playing audio; trivial for the user, unavailable to `adb` without side effects |
| Permanent focus loss recovery via `isMusicActive()` polling | Untested. This is the mechanism that makes *"Stop that app and listening continues by itself"* true rather than aspirational |
| Dim state, light theme, both `Stopped` kinds | Still only verified as previews |
| Multi-hour endurance, drift, battery, OEM kill | Phase 6 / Scenario G |

## Hand-over: the three tests that need a person

1. **Music** — start listening, then play anything for ~10 s. Expect *"Paused · Another app is
   playing sound to your headphones"*, then automatic resume within a couple of seconds of stopping
   it.
2. **Call** — start listening, then take or make a call. Expect *"Paused · A phone call is in
   progress"* — **the wording is the thing being tested**; a call mislabelled as "another app" means
   `getMode()` did not read as a call on this OEM. Expect resume when the call ends.
3. **Microphone** — start listening, then open the voice recorder and record for a few seconds.
   Expect *"Paused · Another app is using the microphone"* and automatic resume when you stop.
   Delete the recording afterwards if you would rather it not exist.

If any of the three pauses but shows the *wrong reason*, that is still a pass for safety — the user
is told they are not hearing the room — and a bug worth fixing in the wording layer only.
