# ADR-0009 — Buffer sizing: double the capture minimum, take the platform minimum on the A2DP output

- **Status:** Accepted
- **Date:** 2026-09-09
- **Supersedes:** [ADR-0006](0006-audio-format-and-buffering.md)

## Context

[ADR-0006](0006-audio-format-and-buffering.md) set buffers at
`max(minBufferSize * 2, frameBytes * 4)` on **both** ends, arguing that "the minimum is what *can*
work, not what works under scheduler pressure", and that the resulting 20–60 ms was invisible
against a 100–250 ms earbud jitter buffer.

The format half of that decision was right and is restated below. **The buffer half was wrong on the
output side**, and the Phase 2 prototype measured it on the first run:

```text
buffers: minRecord=3840 -> 7680, minTrack=20622 -> 41244
```

At 48 kHz mono PCM 16-bit (2 bytes per frame):

| | Platform minimum | ADR-0006 result |
|---|---|---|
| Capture | 3 840 B = 40 ms | 7 680 B = **80 ms** |
| **Output (A2DP)** | **20 622 B = ~215 ms** | 41 244 B = **~430 ms** |

The assumption behind the 2× rule came from the built-in-speaker path, where minimums are on the
order of 20 ms and doubling is genuinely free. **On the A2DP output the platform minimum is already
~215 ms** — the platform has itself budgeted for the Bluetooth link — so doubling it bought no extra
glitch resistance and cost another ~215 ms of latency.

That put app-side buffering alone at roughly **510 ms**, before the earbud's own jitter buffer, which
broke the 180–400 ms end-to-end budget in [feasibility.md](../feasibility.md). The tester's
unprompted description — "noticeably delayed but usable" — matches the arithmetic.

## Decision

**Asymmetric buffer sizing, because the two ends have different platform behaviour:**

```kotlin
val recordBytes = max(minRecord * 2, FRAME_BYTES * 4)  // ~80 ms — cheap insurance
val trackBytes  = max(minTrack,      FRAME_BYTES * 4)  // ~215 ms — already generous
```

Format is unchanged from ADR-0006 and is now measured rather than assumed: **48 000 Hz, mono, PCM
16-bit on both ends, ~20 ms (960-frame) frames, no resampling.** The device confirmed 48 kHz as its
native output rate (`PROPERTY_OUTPUT_SAMPLE_RATE = 48000`), so nothing resamples.

The drift policy from ADR-0006 also stands: instrument `getUnderrunCount()`, frames in versus out
and block times; correct coarsely by dropping or padding one frame at a threshold; no resampler.

## Consequences

- **~215 ms of end-to-end latency removed** — a large fraction of the total, and by far the cheapest
  latency win available to this project. Notably this is a bigger saving than Oboe/AAudio could
  offer ([ADR-0003](0003-audiorecord-audiotrack-for-mvp.md) estimated 20–40 ms), which retrospectively
  strengthens the decision to defer Oboe: the app-side latency problem was a buffer arithmetic bug,
  not a missing low-latency API.
- The latency budget in [feasibility.md](../feasibility.md) Q4 needs correcting: its "`AudioTrack`
  buffer 20–60 ms" row is not achievable on an A2DP route, where ~215 ms is the floor. The revised
  realistic total is roughly **320–500 ms**, not 180–400 ms.
- **Underrun risk on the output side is now the platform's judgement rather than ours.** This is the
  real cost of the change, and it is *(unverified)* over long runs — Scenario G must watch
  `getUnderrunCount()`. If underruns appear, the correct response is a modest multiplier (1.25–1.5×),
  not a return to 2×.
- The asymmetry needs the comment that is now in the code, or someone will "tidy" it back into
  symmetry.
- `minTrack` is route-dependent: a wired or speaker route will report a much smaller minimum, so the
  same expression yields a much smaller buffer there. That is correct behaviour, not a bug, but it
  means **buffer size must be logged per session** rather than assumed constant.

## Alternatives considered

**Keep 2× on both ends** (the ADR-0006 status quo). Rejected on measurement: it doubles the dominant
app-side latency term for no observed benefit.

**Use `minTrack` exactly, with no `frameBytes * 4` floor.** Almost identical in practice on this
route, since 215 ms dwarfs the 7 680 B floor. The floor is retained only so that a route reporting
an implausibly small minimum still gets a workable buffer.

**Chase a lower floor with `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY`.** *(unverified)* Likely
ineffective on A2DP, which typically does not grant a fast mixer path — the same reasoning that
deferred Oboe. Worth one measurement later, not now.

**Reconsider if:** Scenario G shows output underruns at the platform minimum, or if a future
measurement shows latency is dominated by something other than these buffers.
