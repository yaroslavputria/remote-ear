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

## Music and a real phone call — run by the user, 15:14

Reconstructed from `dumpsys audio`, which keeps a focus and mode history that outlives logcat on
this device. **This is why the diagnosis below is a measurement rather than a theory** — our own log
lines had already rotated out of ColorOS's ring buffer.

```text
15:14:10.923  YouTube Music requests focus (USAGE_MEDIA/CONTENT_TYPE_MUSIC)
15:14:11.033  RemoteEar abandons focus                     <- paused, 110 ms later
15:14:23.384  RemoteEar requests focus                     <- resumed by itself once music stopped

15:14:51.768  telecom requests focus (AudioFocus_For_Phone_Ring_And_Calls,
                                      USAGE_VOICE_COMMUNICATION, req=GAIN_TRANSIENT)
15:14:52.205  setMode(MODE_IN_CALL) from com.android.server.telecom   <- +437 ms
15:14:52.287  RemoteEar abandons focus                     <- paused
15:14:58.607  setMode(MODE_NORMAL)                          <- call ends
15:14:59.228  RemoteEar requests focus                     <- resumed 0.6 s later
```

| Check | Result |
|---|---|
| **Music: pause** | **pass** — 110 ms after the other app took focus |
| **Music: automatic resume** | **pass** — the `isMusicActive()` polling works; this is the mechanism that makes *"stop that app and listening continues by itself"* true after a permanent focus loss, where Android sends no `GAIN` |
| **Call: pause** | **pass** |
| **Call: automatic resume when the call ends** | **pass** — 0.6 s |
| **Call: the wording** | **FAIL, now fixed** — it said *another app*, not *a phone call* |

### Why the call was mislabelled — measured, 437 ms

**Telecom takes audio focus 437 ms before the platform enters call mode.** Our focus callback is
what classified the pause, and at that instant `getMode()` still read `MODE_NORMAL` — so a genuine
phone call was labelled *"Another app is playing sound to your headphones"*.

Worth being clear about the severity: **the safety behaviour was correct**. The app paused, said
plainly that the room was not being heard, and resumed by itself. Only the explanation was wrong.
In a product whose central claim is that it tells you the truth about what it is doing, that is
still a defect.

**Fix:** the pause reason is now re-evaluated on every watcher tick rather than decided once, so a
call is relabelled within a second — far faster than anyone reads the screen — and a call that
starts while the app is *already* paused for another reason is also caught. `BluetoothGone` is
deliberately excluded from the upgrade: no headphones is the blocker that needs the user, and it
outlives the call. The audio mode is now logged alongside the focus loss, so if this decision is
ever wrong again it is visible in a bug report instead of needing to be inferred.

### Verified fixed, 15:49 — caught in the act

A second real call landed during the Scenario G run, with our own log streaming this time:

```text
15:49:58.552  focus lost: audio mode=0 -> AudioFocusLost   <- mode 0 = MODE_NORMAL, as diagnosed
15:49:59.446  state -> Paused(reason=AudioFocusLost)        <- the wrong label, briefly
15:49:59.483  state -> Paused(reason=Call)                  <- corrected 37 ms later
15:52:03.986  attempting to resume from Paused(reason=Call)
15:52:04.128  state -> Monitoring(inputSource=Mic, routedInType=15, routedOutType=8)
```

**Pass.** The user confirmed the screen read *"A phone call is in progress."* Three things this
shows that the earlier reconstruction could not:

- `audio mode=0` at the moment of the focus loss — the 437 ms lag is now logged directly by the app
  rather than inferred from `dumpsys`.
- The wrong label existed for **37 ms**, faster than the "within a second" the fix promised.
- The routing assertion held through the automatic resume: `BUILTIN_MIC` → `BLUETOOTH_A2DP`.

## Not run — and these are gaps, not passes

| Scenario | Why not |
|---|---|
| **F — another app takes the microphone** | Reported working by the user, but **not captured**: `dumpsys audio` records focus and mode, not capture clients, so there is no evidence trail for it here. The one thing that would prove it is a log line from our own recording callback, which had rotated out |
| **The call wording after the fix** | The fix landed after the test. Needs one more call |
| Dim state, light theme, both `Stopped` kinds | Still only verified as previews |
| Multi-hour endurance, drift, battery, OEM kill | Phase 6 / Scenario G |

## What is left for a person

One call, on the fixed build, to confirm the label now reads *"A phone call is in progress."* The
pause and the resume are already known to work; only the wording is in question.
