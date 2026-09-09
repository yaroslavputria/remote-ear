# Risks

> Deliverable 8 of the project brief (§21): technical risks ranked by probability and impact.
>
> Ranked by **probability × impact on the product**, not by technical interest. R1 and R2 are the two
> that can make the product not worth shipping.

| # | Risk | Prob. | Impact | Detected by |
|---|---|---|---|---|
| [R1](#r1) | OEM battery manager kills the service mid-session | High | High | Scenarios C, G |
| [R2](#r2) | OEM microphone processing gates away quiet room sound | Medium | High | [H4](feasibility.md), Scenario A |
| [R3](#r3) | Play Store friction over a continuous-microphone app | Medium | High | Phase 7 |
| [R4](#r4) | LE Audio engages the earbud microphone / degrades media | Medium | High | [H2](feasibility.md), Scenario A |
| [R5](#r5) | Clock drift degrades audio over hours | High | Medium | Scenario G |
| [R6](#r6) | Earbud latency worse than the 400 ms budget | Medium | Medium | [H3](feasibility.md), Scenario A |
| [R7](#r7) | Microphone preempted by another app | Medium | Medium | Scenario F |
| [R8](#r8) | Battery drain too high for overnight use | Low | Medium | Scenario G |
| [R9](#r9) | Doze or wakelock stalls the pipeline | Low | Medium | Scenario C, forced idle |
| [R10](#r10) | Platform FGS rules have changed under `targetSdk 36` | Low | Medium | Phase 1 |
| [R11](#r11) | Sleep-sound feature not achievable as specified | Medium | Low (MVP) | Post-MVP spike |

---

## R1 — OEM battery manager kills the service {#r1}

**High probability, high impact.** Xiaomi (MIUI/HyperOS), Huawei (EMUI), Samsung ("Put unused apps
to sleep"), Oppo/Vivo/OnePlus all terminate background processes more aggressively than AOSP
specifies, foreground service or not. No manifest entry compels them to stop.

This is the highest-ranked risk because of *how* it fails: the user believes they are monitoring
their child and they are not. A crash is better than this — a crash is visible.

**Mitigation.** Cannot be prevented, only made honest:
- Detect a session that ended without the user stopping it, and say so on next open (S5).
- Keep the notification informative, so its disappearance is itself a signal.
- Point the user at their own battery settings *after* observing a kill, rather than demanding a
  battery exemption up front.
- If a device kills reliably, document it. "Does not work on X" beats a monitor that stops.

**Explicitly rejected:** requesting `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` by default. Play-policy
risk, and it does not reliably defeat OEM managers anyway.

## R2 — Microphone processing gates away quiet room sound {#r2}

**Medium probability, high impact — the risk that can make the product pointless.** `AudioSource.MIC`
runs the device's tuned input chain: AGC and noise suppression designed to isolate a nearby talker.
For this product the interesting signal is the opposite — quiet, distant, ambient. A noise
suppressor may classify a sleeping room as noise and deliver digital silence, then pump loudly when
the child cries.

The app would "work" perfectly and be useless.

**Mitigation.** `MIC` versus `UNPROCESSED` A/B, built into the Phase 2 prototype while the code is
still disposable, and evaluated in a genuinely quiet room rather than an office. If `UNPROCESSED` is
materially better, S3 promotes to a must-have and software gain (S4) comes with it, since
`UNPROCESSED` is quieter.

## R3 — Play Store friction {#r3}

**Medium probability, high impact.** A continuously-listening app with a microphone-type foreground
service attracts review scrutiny. Rejection does not break the code but does block shipping, and
"baby monitor" framing is safety-adjacent.

**Mitigation.** Handle it in Phase 7 as a real work item, not an afterthought: accurate Data Safety
declaration, prominent disclosure in the permission rationale, a written foreground-service-type
justification, and a store listing that says what the app does (plays a microphone to your
headphones) without promising to detect or alert anything. The absence of `INTERNET` is a genuine
asset in this conversation.

## R4 — LE Audio engages the earbud microphone {#r4}

**Medium probability, high impact.** *(unverified)* LE Audio is bidirectional by design and organised
around audio contexts. A stack may reasonably interpret an active capture stream as a conversational
context, engage the earbud microphone, and reconfigure media at lower quality — producing exactly
the behaviour brief §6 forbids, with the app doing nothing wrong.

Impact is high because LE Audio is the future of the whole headphone axis, and because the failure
is subtle: audio still arrives, from the wrong microphone.

**Mitigation.** Test on real LE Audio hardware early ([H2](feasibility.md)). The `getRoutedDevice()`
assertion (M12) catches the input-side symptom automatically. If the behaviour is unavoidable,
detect `TYPE_BLE_HEADSET` and either prefer an A2DP sink where one exists or warn the user plainly.

## R5 — Clock drift {#r5}

**High probability, medium impact.** Independent crystals in the phone and the earbud guarantee
drift; only the magnitude is unknown. At 100 ppm that is roughly 360 ms of accumulated offset per
hour, appearing as growing latency or accumulating clicks — worsening the longer the monitor runs,
which is backwards for this product.

High probability, but medium impact because the mitigation is known and cheap.

**Mitigation.** Blocking read/write structure to bound the symptom, instrumentation to see it, and
coarse frame drop/pad correction at a threshold. No resampler unless measurement demands one. See
[audio-pipeline.md](audio-pipeline.md).

## R6 — Earbud latency worse than budget {#r6}

**Medium probability, medium impact.** AAC on Android is frequently worse than its reputation, and
some earbuds have large receive buffers. Latency beyond ~500 ms starts to feel disconnected, though
for the baby-monitor use case it remains usable.

**Mitigation.** Nothing in the app can fix it; the term lives in the headphones. Measure per
headphone class, and if latency is a real complaint, document which codecs behave. Oboe/AAudio is
*not* the answer ([ADR-0003](adr/0003-audiorecord-audiotrack-for-mvp.md)) — it addresses the small
terms, not the large one.

## R7 — Microphone preempted {#r7}

**Medium probability, medium impact.** Since Android 10 the microphone has a single owner; an
assistant, a call, or a camera can take it. The app receives silence or a read error, not a tidy
callback.

**Mitigation.** Treat read errors and sustained exactly-zero-frame runs as preemption, retry with
backoff, and surface `Paused(MicPreempted)` rather than pretending to monitor (S6). The general rule
applies: never present silence as monitoring.

## R8 — Battery drain {#r8}

**Low probability, medium impact.** The pipeline itself moves 96 kB/s and costs almost no CPU; the
draw is the microphone, the audio DSP path, and the Bluetooth radio. Several hours should be
comfortable, but this is an estimate, not a measurement.

**Mitigation.** Measure in Scenario G before optimising anything. Brief §13 is right that
reliability should not be traded for small battery savings — and the L5 "reduce processing while
quiet" idea should not be built on a guess.

## R9 — Doze or wakelock stalls {#r9}

**Low probability, medium impact.** A foreground service with an actively playing `AudioTrack` should
keep the pipeline alive; screen-off is not Doze. But "should" is doing work in that sentence.

**Mitigation.** Force idle in testing (`dumpsys deviceidle force-idle`) rather than waiting for
natural Doze. If stalls appear, add a `PARTIAL_WAKE_LOCK` — and record an ADR, because it is a new
permission and therefore a decision, not a tweak.

## R10 — Platform rules have changed {#r10}

**Low probability, medium impact.** The foreground-service documentation in
[android-constraints.md](android-constraints.md) was written from knowledge with a cutoff, and this
area of the platform moves quickly — service-type timeouts and background-start rules have both
changed in recent releases.

**Mitigation.** Phase 1 exists solely to eliminate this, before any service code is written.

## R11 — Sleep-sound feature not achievable {#r11}

**Medium probability, low MVP impact.** Brief §23 assumes echo cancellation is the hard part. The
prior blocker is routing: while a Bluetooth sink is connected, Android routes media to it, so
speaker noise plus earbud monitoring requires two concurrent differently-routed output streams, and
whether `setPreferredDevice(TYPE_BUILTIN_SPEAKER)` is honoured is device-dependent. If it is not,
the feature is not achievable as specified regardless of how good the cancellation is.

Low impact on the MVP by construction — the feature is post-MVP and gated.

**Mitigation.** [ADR-0008](adr/0008-sleep-sound-deferred.md) orders the spike so the cheapest
disqualifying question is asked first. Its question (c) also offers a genuinely easier path than
full AEC: the noise is self-generated and stationary, so its spectrum is known exactly, which makes
spectral subtraction plausible where general echo cancellation is not.

---

## Risks that are *not* on this list

Worth stating, because their absence is a decision:

- **React Native bridge performance.** Removed by [ADR-0001](adr/0001-native-kotlin-over-react-native.md).
  Brief §5 correctly notes it was never the real risk anyway.
- **Audio quality of the codec.** SBC is adequate for a room monitor. Chasing fidelity here is the
  premature optimisation brief §4 warns against.
- **Data leaking to a server.** There is no server and no `INTERNET` permission. Structurally absent,
  not mitigated.
