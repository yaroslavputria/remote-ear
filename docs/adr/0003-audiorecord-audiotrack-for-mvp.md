# ADR-0003 — Use the Kotlin SDK `AudioRecord`/`AudioTrack` for the MVP; defer Oboe/AAudio

- **Status:** Accepted
- **Date:** 2026-09-09

## Context

Brief §4 lists low latency as the second priority after reliability, and §14 targets "as close to
realtime as practical using standard Android audio APIs" while accepting "a few hundred
milliseconds".

Oboe (over AAudio) is the standard answer for low-latency Android audio, and would be the obvious
choice for an instrument or a game. The question is whether it earns its cost here.

The latency budget ([feasibility Q4](../feasibility.md)):

| Stage | Typical | Oboe helps? |
|---|---|---|
| Capture buffer | 20–40 ms | somewhat |
| Application loop | 10–20 ms | somewhat |
| Track buffer | 20–60 ms | somewhat |
| Mixer + A2DP encode | 20–40 ms | no |
| **Earbud jitter buffer + codec** | **100–250 ms** | **no** |

## Decision

**Use `AudioRecord` and `AudioTrack` from the Kotlin SDK.** No NDK, no C++, no Oboe in the MVP.

## Consequences

- The pipeline is about 30 lines of Kotlin instead of a C++ layer plus a JNI boundary plus a CMake
  build. For an app whose entire logic is "read a buffer, write a buffer", that is the whole
  argument.
- No NDK build, no ABI splits, no native crash class, no separate symbol handling.
- **We give up perhaps 20–40 ms** of the application-side terms. Against a total dominated by a
  100–250 ms buffer inside the earbud, that is roughly a 10% change in end-to-end latency — likely
  imperceptible for this use case, and certainly not worth an NDK toolchain.
- A further point: A2DP output typically does not grant a fast/low-latency mixer path anyway, so
  Oboe's headline benefit is substantially unavailable on the only output route this product uses.
- Java-side buffers mean the loop must avoid allocation to avoid GC pauses. That is a discipline
  rather than a design problem, and it is written down in
  [audio-pipeline.md](../audio-pipeline.md).

## Alternatives considered

**Oboe/AAudio now.** Correct for a professional audio app. Here it optimises the small terms of the
budget while leaving the large one untouched, and adds a build system, a language boundary, and a
crash class to a five-class application.

**Reconsider if:** [H3](../feasibility.md) measurements show application-side latency is a real
complaint on hardware where the *link* latency is already low (LC3/LE Audio, aptX LL) — that is the
only regime where the application-side terms are a meaningful share of the total. Also revisit if
the post-MVP sleep-sound work ([ADR-0008](0008-sleep-sound-deferred.md)) requires
sample-aligned full-duplex processing, which is a genuine Oboe strength.
