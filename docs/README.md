# RemoteEar documentation

Planning corpus for RemoteEar — an Android phone used as a remote microphone, played to Bluetooth
headphones. These documents are deliverables 1–9 of §21 of the project brief, plus the evidence
gathered since. **Phase 2 has proven the core use case on real hardware**; the disposable prototype
that proved it lives in [`app/`](../app/).

## Read in this order

| # | Document | Answers |
|---|---|---|
| 1 | [feasibility.md](feasibility.md) | Is this possible on modern Android? Which APIs? What could go wrong? |
| 2 | [audio-pipeline.md](audio-pipeline.md) | The capture-to-playback design: formats, buffers, threading, drift, state machine |
| 3 | [android-constraints.md](android-constraints.md) | Permissions, foreground service, background execution, Bluetooth routing, Play policy |
| 4 | [mvp-scope.md](mvp-scope.md) | Must have / should have / later / explicitly out of scope |
| 5 | [implementation-plan.md](implementation-plan.md) | Phased steps, each with a gate that must pass |
| 6 | [test-matrix.md](test-matrix.md) | Devices, headphones, scenarios A–G, pass criteria |
| 7 | [risks.md](risks.md) | Ranked by probability × impact on the product |
| 8 | [privacy.md](privacy.md) | The invariants, and how they are structurally enforced |
| — | [adr/](adr/) | Architecture Decision Records — the decisions themselves |
| — | [dev-setup.md](dev-setup.md) | Toolchain install (no admin rights), and the traps found doing it |
| — | [design-brief.md](design-brief.md) | Input for UI design work — states, microcopy, constraints |
| — | [test-runs/](test-runs/) | Results from real devices, one file per session |

If you only read one thing, read [ADR-0004](adr/0004-media-path-only.md). It is the constraint the
rest of the design hangs from.

## Decisions at a glance

| | |
|---|---|
| **Stack** | Native Kotlin + Jetpack Compose ([ADR-0001](adr/0001-native-kotlin-over-react-native.md)) |
| **Platform** | `minSdk` 29, `targetSdk` 36 ([ADR-0002](adr/0002-min-and-target-sdk.md)) |
| **Audio APIs** | `AudioRecord` + `AudioTrack`; Oboe deferred ([ADR-0003](adr/0003-audiorecord-audiotrack-for-mvp.md)) |
| **Routing** | **Media path only — never Bluetooth SCO** ([ADR-0004](adr/0004-media-path-only.md)) |
| **Background** | Foreground service, type `microphone`, survives pauses ([ADR-0005](adr/0005-foreground-service-hosts-monitoring.md)) |
| **Format** | 48 kHz, mono, PCM 16-bit, no resampling ([ADR-0009](adr/0009-buffer-sizing-measured.md)) |
| **Buffers** | 2× minimum on capture; **platform minimum** on the A2DP output ([ADR-0009](adr/0009-buffer-sizing-measured.md)) |
| **Permissions** | Four. No `INTERNET`, no Bluetooth, no location ([ADR-0007](adr/0007-minimal-permission-set.md)) |
| **Sleep sound** | Deferred behind an ordered spike ([ADR-0008](adr/0008-sleep-sound-deferred.md)) |

## Current state

**Phases 0, 1 and 2 complete.** The question the whole project rests on is answered on hardware
([test run](test-runs/2026-09-09-oneplus-cph2399.md), OnePlus CPH2399 / Android 14):

> Can I put the phone in another room and reliably hear its microphone through my Bluetooth earbud?
> **Yes.**

Routing verified as `in=BUILTIN_MIC out=BLUETOOTH_A2DP` with no SCO anywhere — and `BLUETOOTH_SCO`
*was* offered in the device list and correctly declined, the first hardware evidence that
[ADR-0004](adr/0004-media-path-only.md) holds. A quiet room stayed audible
([risk R2](risks.md) not landing), and frames in equalled frames out exactly over 27 seconds.

Both phases corrected our own assumptions rather than merely confirming them:

- **Phase 1** found a stricter *while-in-use* background-start restriction (which strengthens
  [ADR-0005](adr/0005-foreground-service-hosts-monitoring.md)); that losing the microphone delivers
  **silence, not an error**, promoting `isClientSilenced()` detection to must-have M15; and an
  audio-focus ordering constraint on Android 15+. [Risk R10](risks.md) is closed.
- **Phase 2** found that the platform's minimum `AudioTrack` buffer on A2DP is already ~215 ms, so
  the 2× rule had doubled it. Removing that recovered ~215 ms — more than Oboe was estimated to
  offer ([ADR-0009](adr/0009-buffer-sizing-measured.md) supersedes
  [ADR-0006](adr/0006-audio-format-and-buffering.md)). The latency budget is revised upward to
  **~435–605 ms**.

**Next: Phase 3** — move the pipeline into a `microphone` foreground service so monitoring survives
backgrounding and screen lock. Confirmed necessary the hard way: with no service, monitoring stopped
the moment the app was backgrounded.

Still open on hardware: a numeric latency figure (no clap test yet), LE Audio (H2 — no hardware), an
OEM baseline (no near-AOSP device), and all Android 15/16 behaviour (untestable on API 34). The
toolchain is installed without admin rights — see [dev-setup.md](dev-setup.md). No emulator is
installed, deliberately: it has no Bluetooth audio and can settle none of the open hypotheses.

## Conventions

- **Unverified claims are marked.** Anything tagged *(measure)* or *(unverified)* is a hypothesis,
  not a fact. Several of them are load-bearing, and the test matrix exists to settle them.
- **Decisions go in ADRs.** These documents describe design and evidence; `adr/` records choices with
  their alternatives and consequences. Changing a decision means writing a new ADR that supersedes
  the old one, not editing history.
- **Test results are evidence, not summaries.** Record what happened, including boring passes and
  what was not tested — see [test-runs/TEMPLATE.md](test-runs/TEMPLATE.md).
