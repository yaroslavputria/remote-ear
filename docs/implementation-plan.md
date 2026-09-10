# Implementation plan

> Deliverables 6 and 9 of the project brief (§21): the smallest prototype that proves the core use
> case, then implementation in small independently testable steps.

## How to read this

Every phase has a **gate**: a concrete, observable condition. A gate is not "the code compiles" or
"it looks right" — it is something that either happened on a physical device or did not.

**Do not start a phase before the previous gate passes.** The brief's §18 sequencing exists because
the expensive mistake in this project is building product on top of an audio path that turns out not
to work. Two gates (Phase 2 and Phase 6) are genuine go/no-go points for the whole project.

Phases 2 onward require **physical Android hardware with real Bluetooth headphones**. The emulator
has no Bluetooth audio and can verify none of it.

---

## Phase 0 — Planning documents

Turn the brief into a versioned planning corpus: feasibility, pipeline design, platform constraints,
scope, this plan, test matrix, risks, privacy, and the ADRs. Set up `CLAUDE.md` and the project
skills so later sessions stay inside these decisions.

**Gate:** committed. ✅ *(this commit)*

## Phase 1 — Verify the platform documentation

Small, boring, and load-bearing. Re-check the foreground-service and microphone rules against the
current `developer.android.com` for `targetSdk 36`, and amend
[android-constraints.md](android-constraints.md) with what is actually true today.

**Gate:** [android-constraints.md](android-constraints.md) carries no unverified platform claims,
and the four questions in its verification task are answered in writing. ✅ *(2026-09-09)*

It paid for itself. Three things were not in the original desk research:

1. **A second, stricter background-start layer.** `RECORD_AUDIO` is a *while-in-use* permission, so
   starting a `microphone` service from the background raises a `SecurityException` (Android 14+),
   with a much shorter exemption list than the general ban. It applies **only to starting** a
   service, not to one already running — which strengthens
   [ADR-0005](adr/0005-foreground-service-hosts-monitoring.md) rather than undermining it.
2. **Losing the microphone delivers silence, not an error**, and there is a platform API for
   detecting it — `isClientSilenced()`, available at API 29. This replaced a zero-frame heuristic in
   the design. It also revealed that `AudioSource.MIC` is not *privacy-sensitive*, so we
   structurally lose the microphone to any VoIP app ([risk R7](risks.md)).
3. **Audio focus requires the foreground service** when targeting Android 15+ — an ordering
   constraint: request focus after `startForeground()`, never from the Activity.

Confirmed as expected: `FOREGROUND_SERVICE_MICROPHONE` is correct; the `microphone` type has **no**
timeout, so multi-hour sessions are permitted; Android 16 changes nothing in this area.
[Risk R10](risks.md) is closed, to be reopened when `targetSdk` next rises.

*Why first: it is cheap, and it is the one thing in the plan that could invalidate the service
design before a line of it is written.*

## Phase 2 — Throwaway prototype: can I hear the room?

**The most important phase in the project.** One Activity, one button, no service, no architecture,
no tests. Deliberately disposable code whose only job is to answer one question.

- `AudioRecord(MIC)` pinned to `TYPE_BUILTIN_MIC` → `AudioTrack(USAGE_MEDIA)` pinned to the
  Bluetooth sink, on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` thread. Formats and buffer sizing per
  [audio-pipeline.md](audio-pipeline.md).
- Log, on start: negotiated sample rate and buffer sizes, `record.routedDevice.type`,
  `track.routedDevice.type`, and the full output-device list. **This log is the deliverable** — it is
  the evidence for [H1 and H2](feasibility.md).
- Add the `MIC` / `UNPROCESSED` A/B toggle now, while the code is disposable, and listen to both in
  a genuinely quiet room ([H4](feasibility.md)).
- Clap test for latency ([H3](feasibility.md)).

**Gate (project go/no-go): PASSED 2026-09-09** —
[test run](test-runs/2026-09-09-oneplus-cph2399.md), OnePlus CPH2399 / Android 14.

| | Condition | Result |
|---|---|---|
| 1 | Standing in another room, the phone's microphone is clearly audible in the earbud | **pass** |
| 2 | `routedDevice` is `TYPE_BUILTIN_MIC` in and A2DP/BLE out — never `TYPE_BLUETOOTH_SCO` | **pass** — and `BLUETOOTH_SCO` was offered in the device list and correctly declined |
| 3 | Measured latency within the 180–400 ms budget | **failed as budgeted** — subjectively "noticeably delayed but usable"; cause found and fixed, budget corrected, re-test pending |
| 4 | A quiet room is audible as a quiet room, not gated to digital silence | **pass** — the most valuable result, see [risk R2](risks.md) |

> **The answer to the question the whole project rests on is yes.** Gate 3 is the exception, and it
> failed in an informative way: the log showed the platform's minimum `AudioTrack` buffer on A2DP is
> already ~215 ms, and [ADR-0006](adr/0006-audio-format-and-buffering.md)'s 2× rule had doubled it.
> Removing that recovered ~215 ms — more than Oboe was estimated to offer
> ([ADR-0009](adr/0009-buffer-sizing-measured.md) supersedes ADR-0006). The corrected budget is
> ~435–605 ms.
>
> Also learned, for Phase 6: **do not read counters from logcat.** ColorOS is chatty enough to rotate
> them out of the ring buffer within a minute. Read them from the app.

## Phase 3 — Move the pipeline into a foreground service

Now that the audio path is proven, give it somewhere to live.

- `MonitoringService` with `foregroundServiceType="microphone"`, started from the visible Activity.
- Notification: low-importance channel, ongoing, live state text, `Stop` action.
- Permission sequencing per [android-constraints.md](android-constraints.md) — `RECORD_AUDIO` before
  promotion.
- The pipeline moves in essentially unchanged; the service owns its lifecycle.

**Gate:** Scenarios C and D — monitoring continues with the app backgrounded, and with the screen
locked, for at least 30 minutes. The notification reflects the true state.

## Phase 4 — UI

- Compose screen matching brief §9: title, Bluetooth dot, microphone dot, START/STOP, volume slider.
- `MonitorViewModel` exposing a single `StateFlow<MonitorState>`; the state machine from
  [audio-pipeline.md](audio-pipeline.md) is the source of truth and lives in the service.
- Permission flow with a rationale before the request, and a route to app settings after a permanent
  denial.
- Bluetooth presence and hotplug via `AudioManager.registerAudioDeviceCallback`.
- The local-only privacy statement (M13) on the main screen.

**Gate:** every state in the machine is reachable and correctly rendered, including each `Paused`
reason. Denying microphone permission produces an explanation, not a dead button.

## Phase 5 — Robustness

The phase that turns a demo into something you would leave running near a child.

- Audio focus: request on start, pause on transient loss, resume on gain, stop on permanent loss.
- Phone/VoIP call: pause, never interfere, resume when it ends.
- Bluetooth disconnect: pause with `BluetoothGone` and the message from brief §16. Auto-resume on
  reconnect (S1) — the service stays alive, which is what makes this possible at all.
- Microphone preemption: interpret read errors and sustained zero-frame runs, retry with backoff,
  surface honestly after repeated failure.
- Wrong-routing detection as a hard error with the observed device types shown.

**Gate:** Scenarios E and F pass, including recovery. Nothing in this phase results in a silent
stop.

## Phase 6 — Long run, drift, and battery

- Implement the counters from [audio-pipeline.md](audio-pipeline.md): underruns, read/write block
  time, frames in versus out. Periodic summary logs, never per frame.
- Implement the coarse drift correction (drop or pad one frame at a threshold) and confirm it is
  inaudible.
- Full Scenario G run with `batterystats`, on more than one device.
- Record the real numbers in `docs/test-runs/`.

**Gate (second go/no-go):** several hours of continuous monitoring with no audible degradation, no
crash, no silent stop, and a battery figure that makes the product usable overnight. Answers
[H5, H6, H7](feasibility.md).

*This is where an unglamorous truth may surface: if a given OEM kills the service after 40 minutes,
the product does not work on that OEM, and saying so is better than shipping a monitor that stops.*

## Phase 7 — Polish and release readiness

- Privacy copy, app icon, notification wording.
- The S-tier items from [mvp-scope.md](mvp-scope.md) that survived triage.
- R8/minify release build, verify the release APK's merged manifest contains **no `INTERNET`
  permission** and exactly the four expected permissions.
- Play Data Safety declaration, prominent-disclosure check, and foreground-service-type
  justification, per the Play section of [android-constraints.md](android-constraints.md).
- Store listing that describes what the app does and promises nothing about detecting anything.

**Gate:** releasable.

---

## Post-MVP: the sleep-sound spike

Brief §23. **Not** part of the MVP, and not startable before the Phase 6 gate. Its spike answers
three questions **in this order**, because each makes the next moot:

1. **Can two media streams route to the speaker and to A2DP simultaneously?** `setPreferredDevice`
   `TYPE_BUILTIN_SPEAKER` on a second `AudioTrack`, then `getRoutedDevice()` to check whether the
   audio policy honoured it. If not, the feature as specified is not achievable and (2) and (3) do
   not matter.
2. **Is any platform echo cancellation usable off the communication path?**
   `AcousticEchoCanceler` is documented as reliable with `VOICE_COMMUNICATION`, which
   [ADR-0004](adr/0004-media-path-only.md) forbids. Probably unusable — verify rather than assume.
3. **Is cheap known-spectrum suppression enough instead of true AEC?** We generate the noise, and
   white noise is stationary, so its spectrum is known exactly and does not change. Spectral
   subtraction or an adaptive Wiener filter against a known stationary spectrum is a far smaller
   problem than full acoustic echo cancellation of arbitrary audio.

See [ADR-0008](adr/0008-sleep-sound-deferred.md). Do not begin with (2) or (3), however interesting
they are.

## Sequence at a glance

```text
 0  docs ............................ committed                   [done]
 1  verify platform docs ............ no unverified claims        [done]
 2  throwaway prototype ............. HEAR THE ROOM               [PASSED]
 3  foreground service .............. scenarios C, D            <-- next
 4  Compose UI ...................... all states reachable
 5  robustness ...................... scenarios E, F
 6  long run + battery .............. scenario G                  [GO / NO-GO]
 7  polish + release ................ releasable
 -  sleep-sound spike ............... answers (a) -> (b) -> (c)
```
