# ADR-0001 — Build RemoteEar as a native Kotlin app, not React Native

- **Status:** Accepted
- **Date:** 2026-09-09

> Deliverable 2 of the project brief (§21): recommended architecture. Compares fully native
> Kotlin against React Native + a Kotlin native audio module, and recommends one.

## Context

Brief §7 says React Native "is acceptable and initially preferred if it does not create unnecessary
complexity", and then asks two pointed questions in §18: *"Can this be implemented reliably with
React Native + Kotlin?"* and *"Would native Kotlin be materially simpler?"* — followed by *"Do not
choose React Native merely because it is familiar."*

The relevant fact is the division of labour. In **either** design the following are native Kotlin:

- the realtime `AudioRecord` → `AudioTrack` loop,
- the foreground service that keeps the microphone alive in the background,
- audio device enumeration and routing,
- audio focus and interruption handling.

React Native's share would be the screen sketched in brief §9: a title, two status dots, one
button, one slider. The JavaScript thread would never see an audio sample.

## Decision

**Native Kotlin with Jetpack Compose.** A single Gradle module, no cross-platform layer, no
JavaScript.

## Consequences

**Gained**

- No Node/Metro toolchain, no `node_modules`, no TurboModule codegen, no bridge boilerplate for the
  start/stop calls and the status event stream.
- Roughly 20–40 MB smaller APK and a faster cold start — proportionate for a utility whose entire UI
  is one screen.
- Fewer failure modes. The bridge, the JS runtime, and the RN upgrade treadmill all disappear.
- A supply-chain argument that matters for a microphone app: essentially no third-party runtime
  dependencies beyond AndroidX/Compose, which supports the privacy claims in
  [privacy.md](../privacy.md) — fewer things the user has to take on faith.

**Lost**

- No path to iOS from this codebase. Accepted: iOS does not permit this use case in the same way
  (background microphone capture routed to Bluetooth output is not available to third-party apps on
  comparable terms), so a shared UI layer would have had nothing to share.
- No React Native experience gained from this project.
- Compose is now on the critical path. Low risk — this is one screen of standard widgets.

**Follow-on**

- The `react-native-best-practices` skill is removed from `.claude/skills/`. It is not merely inert:
  it declares itself mandatory for "any audio feature" and triggers on `audio`, `recording`, and
  `playback`, so in a native Kotlin repo it would inject React Native audio guidance into exactly
  the highest-stakes tasks.
- `CLAUDE.md` overrides the general Android house defaults that do not fit an app of this size — no
  Hilt, no Retrofit, no Room, no Coil.

## Alternatives considered

**React Native + a Kotlin TurboModule.** Viable and reliable — brief §5 is right that RN was never
the real technical risk. It loses on cost/benefit: it adds a toolchain, a build step, and a bridge
in order to write ~150 lines of TSX instead of ~150 lines of Compose.

**Flutter or KMP.** Same objection, plus less mature access to the Android audio APIs that constitute
the entire product.

**Reconsider if:** the product grows a substantial multi-screen UI *and* an iOS version becomes
possible. Neither is on the roadmap; brief §22 argues against both.
