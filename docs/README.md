# RemoteEar documentation

Planning corpus for RemoteEar — an Android phone used as a remote microphone, played to Bluetooth
headphones. **No application code exists yet**; these documents are deliverables 1–9 of §21 of the
project brief, written so that Phase 2 (the prototype that proves the core use case) is cheap to
start and hard to get wrong.

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
| **Format** | 48 kHz, mono, PCM 16-bit, no resampling ([ADR-0006](adr/0006-audio-format-and-buffering.md)) |
| **Permissions** | Four. No `INTERNET`, no Bluetooth, no location ([ADR-0007](adr/0007-minimal-permission-set.md)) |
| **Sleep sound** | Deferred behind an ordered spike ([ADR-0008](adr/0008-sleep-sound-deferred.md)) |

## Current state

Phase 0 complete. **Next: Phase 1** — re-verify the foreground-service rules against current
platform documentation, then **Phase 2**, the throwaway prototype whose only job is to answer:

> Can I put the phone in another room and reliably hear its microphone through my Bluetooth earbud?

## Conventions

- **Unverified claims are marked.** Anything tagged *(measure)* or *(unverified)* is a hypothesis,
  not a fact. Several of them are load-bearing, and the test matrix exists to settle them.
- **Decisions go in ADRs.** These documents describe design and evidence; `adr/` records choices with
  their alternatives and consequences. Changing a decision means writing a new ADR that supersedes
  the old one, not editing history.
- **Test results are evidence, not summaries.** Record what happened, including boring passes and
  what was not tested — see [test-runs/TEMPLATE.md](test-runs/TEMPLATE.md).
