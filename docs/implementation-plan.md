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

**Gate: PASSED 2026-09-10** —
[test run](test-runs/2026-09-10-oneplus-cph2399-phase3.md), OnePlus CPH2399 / Android 14.

Scenarios C and D both pass: monitoring continued with the app backgrounded and with the screen
locked. Verified against the platform's own view rather than just the tester's report —
`isForeground=true`, `types=00000080` (= `FOREGROUND_SERVICE_TYPE_MICROPHONE`), `createdFromFg=true`,
a low-importance notification carrying the Stop action, and the routing assertion still reading
`in=BUILTIN_MIC out=BLUETOOTH_A2DP` through a service-hosted session.

> **The "at least 30 minutes" part of this gate was not met** — the service had been up 2 m 25 s
> when measured. The *mechanism* is proven; endurance is not, and a 30-minute run would not settle
> it either. The multi-hour question is Scenario G in Phase 6, which exists for exactly this and
> targets the risk that actually threatens it ([R1](risks.md), OEM process kill). Phase 4 proceeds
> on the mechanism; the duration evidence comes from Phase 6.

## Phase 4 — UI — **built 2026-09-10, rendering unverified on hardware**

Built from [design-spec.md](design-spec.md), the design returned against
[design-brief.md](design-brief.md). What shipped:

- The designed screen: state badge, headline, reason, supporting text, one 92 dp control, the
  four-item status panel, the volume readout, the noise slider, the privacy footnote.
- `MonitorViewModel` collapsing the service's state plus permission, Bluetooth presence and the
  phone's media volume into a single `StateFlow<MonitorUiState>`. The state machine still lives in
  the service and remains the source of truth.
- A `Screen` type distinct from `MonitorState`: "no permission" and "no headphones" are screens the
  design specifies but the pipeline has no state for.
- All copy in `strings.xml`, read by **both** the screen and the notification, so the two cannot
  disagree — the design's central rule.
- Every state as a `@Preview`, including all four pause reasons and both error kinds.
- The palette, with dynamic colour deliberately off, and the designed adaptive icon.
- **Volume slider dropped** — brief §9 asked for one; [M10](mvp-scope.md) settled that loudness is
  the phone's media volume and an app control could only attenuate. The screen shows the level and
  says where to change it.
- Two things the design added and the brief had not asked for: the dimmed listening state
  ([S8](mvp-scope.md)) and the app icon.

Fixed on the way through: the service classified a failed start by **substring-matching the error
message** for "Bluetooth", and every routing-failure message contains `BLUETOOTH_SCO` — so the one
failure [ADR-0004](adr/0004-media-path-only.md) exists to catch would have been reported as
"headphones disconnected, reconnect to continue". Now an enum, `FailureCause`, decided at the site of
the failure.

**Gate:** every state in the machine is reachable and correctly rendered, including each `Paused`
reason. Denying microphone permission produces an explanation, not a dead button.

**Gate status: partially met.** Reachability is met — each state is a literal in
`MonitorPreviews.kt`, and the permission screen carries a rationale and an "Allow microphone" button
rather than a dead control. *Correctly rendered* is **not** verified: the build installs and the
process runs without crashing, but nothing has yet looked at the screens on a device or in a preview
renderer. Still to check by eye: the four pause reasons, the two error kinds, the dim state and its
touch-to-wake, the light theme, and whether the fixed layout holds without clipping.

## Phase 5 — Robustness — **built 2026-09-10; Scenario E verified, F not run**

The phase that turns a demo into something you would leave running near a child.

Four things can take the room away, and each is now noticed, named and recovered from:

| What happens | Detected by | Recovery |
|---|---|---|
| A call starts | audio focus loss, with `getMode()` reading as a call | polls until the call ends |
| Another app plays audio | audio focus loss | `AUDIOFOCUS_GAIN`, or polling `isMusicActive()` |
| Headphones disconnect | `AudioDeviceCallback` | on reconnect, with a retry (S1) |
| Another app takes the microphone | `isClientSilenced()` | when the flag clears |

All transitions funnel through one `Mutex`, because they now arrive from three places at once — the
focus listener and the device callback on the main thread, the watcher on a background dispatcher —
and two interleaving would open streams another had just closed.

**Three deliberate departures from the plan as written:**

1. **A permanent focus loss pauses rather than stops.** The plan said stop. But the designed copy
   promises *"stop that app and listening continues by itself"*, and after `AUDIOFOCUS_LOSS` Android
   does not send a `GAIN` — so waiting for one would wait forever and make the sentence a lie.
   Instead the watcher polls `isMusicActive()` and re-requests focus. Coarse, and it keeps the
   promise.
2. **A microphone preemption keeps its streams open.** Every other pause releases the microphone and
   the focus — during a call, holding the microphone would be indefensible. But the recording
   callback on our own live `AudioRecord` is the only thing that can report the *end* of a
   silencing, so releasing it would mean guessing with a backoff timer. The user-facing claim is
   honest either way: they are not hearing the room.
3. **Reading the audio mode**, to tell a call from music. Both arrive as an identical focus loss, and
   the alternative is `READ_PHONE_STATE`. `getMode()` is a read;
   [ADR-0004](adr/0004-media-path-only.md) prohibits *setting* it, and the invariant guard was
   narrowed from the constant names to `setMode(` and `.mode =` — strictly stronger against the
   thing actually prohibited.

**Gate:** Scenarios E and F pass, including recovery. Nothing in this phase results in a silent
stop.

**Gate status: half met.** **Scenario E passes, twice, with evidence**
([run](test-runs/2026-09-10-oneplus-cph2399-phase5.md)): pause is immediate, screen and notification
both carry the reason verbatim, auto-resume takes ~5 s unattended, and the routing assertion still
reads `BUILTIN_MIC` → `BLUETOOTH_A2DP` afterwards — an automatic resume onto SCO would have been the
nightmare case. The retry backoff proved necessary on the first attempt: Android reports the A2DP
sink as usable about a second before it will accept a stream.

**Scenario F has not been run.** Provoking it from `adb` means recording the user's room to a file,
which is not mine to do. The call-reason wording and the `isMusicActive()` recovery are also
unverified. Those three need a person, and the steps are in the run record.

## Phase 6 — Long run, drift, and battery — **instrumented 2026-09-10; the run is what remains**

- Implement the counters from [audio-pipeline.md](audio-pipeline.md): underruns, read/write block
  time, frames in versus out. Periodic summary logs, never per frame. ✅
  Also **maxima, not just means** — a single 300 ms stall in four hours is an audible glitch and is
  invisible in an average over 700,000 frames — plus elapsed time, peak backlog and a count of each
  kind of correction.
- Implement the coarse drift correction (drop or pad one frame at a threshold) and confirm it is
  inaudible. **Implemented**; whether it is inaudible, or ever even fires, is what the run answers.
- Full Scenario G run with `batterystats`, on more than one device.
- Record the real numbers in `docs/test-runs/`.

**The run splits in two, because the phone cannot be both measured and charged.** It was plugged in
and at 100 %, and unplugging it also takes `adb` away — and with it the per-minute time series,
since ColorOS rotates our lines out of the ring buffer within a minute.

| | Run 1 — plugged in, `adb` attached | Run 2 — unplugged, unattended |
|---|---|---|
| Answers | drift, underruns, stalls, thermals, no-silent-stop | battery drain, OEM process kill ([R1](risks.md)) |
| Evidence | per-minute `SUMMARY` lines streamed to a file | battery % delta, `batterystats`, and the app's own end-of-run counters |
| Hypotheses | [H7](feasibility.md) | [H5, H6](feasibility.md) |

Run 2 needs no `adb` on purpose: the counters are cumulative and peak-tracking precisely so that a
**snapshot at the end is still meaningful**. What it loses is *when* — steady growth versus one
jump — which is what Run 1 is for.

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
 3  foreground service .............. scenarios C, D              [PASSED]
 4  Compose UI ...................... all states reachable       <-- next
 5  robustness ...................... scenarios E, F
 6  long run + battery .............. scenario G                  [GO / NO-GO]
 7  polish + release ................ releasable
 -  sleep-sound spike ............... answers (a) -> (b) -> (c)
```
