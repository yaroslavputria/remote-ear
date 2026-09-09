# ADR-0006 — 48 kHz mono PCM 16-bit, no resampling; buffers at 2× minimum; drift corrected coarsely

- **Status:** Superseded by [ADR-0009](0009-buffer-sizing-measured.md) — the buffer policy below was measured wrong on the A2DP output; the format decision stands
- **Date:** 2026-09-09

## Context

Brief §13 asks for investigation of buffer sizes, sample rate, and channel count with an eye to
battery; §4 ranks reliability first, low latency second, and *natural environmental sound* third;
§14 warns against "architectures that introduce unnecessary buffering".

Two additional facts shape the choice:

- Essentially all modern Android audio hardware runs at **48 kHz**. Requesting anything else inserts
  a platform resampler — latency and CPU for no benefit.
- The phone's microphone ADC and the earbud's DAC are driven by **independent crystals**, so their
  sample clocks drift. This is not a bug to be fixed but a physical property to be managed.

## Decision

**48 000 Hz, mono, PCM 16-bit, on both ends. Frames of ~20 ms. Buffers at 2× the platform minimum.
Drift corrected by dropping or padding a single frame.**

| Parameter | Value | Reason |
|---|---|---|
| Sample rate | 48 000 Hz | Native rate — nothing resamples |
| Channels | mono | One built-in microphone; `AudioTrack` handles mono-to-stereo for the sink |
| Encoding | PCM 16-bit | Ample for a room; float is discarded by the A2DP encoder anyway |
| Frame | 960 frames = 1920 bytes ≈ 20 ms | Small enough for latency, large enough that per-call overhead vanishes |
| Buffers | `max(minBufferSize × 2, frameBytes × 4)` | Minimum is what *can* work, not what works under scheduler pressure |

Throughput is 96 kB/s.

**Drift policy:** instrument `getUnderrunCount()`, read/write block time, and frames in versus out.
When a threshold is crossed, drop one frame (capture running ahead) or write one frame of silence
(playback running ahead). **No resampler in the MVP.**

## Consequences

- No resampling anywhere in the path.
- 96 kB/s of memory copying is CPU-negligible, so battery is dominated by the microphone, the audio
  DSP path, and the Bluetooth radio — not by our format choice. Dropping to 16 kHz would save
  nothing measurable while costing the "natural environmental sound" priority.
- ~40–120 ms of buffer latency, against an earbud jitter buffer of 100–250 ms
  ([feasibility Q4](../feasibility.md)). Doubling the minimum buffer is invisible in the total and
  buys real glitch resistance — the right side of the reliability-versus-latency trade that §4 sets
  up.
- **Drift is handled coarsely and visibly rather than correctly and invisibly.** A 20 ms correction
  every few minutes is inaudible; the drift it corrects (~360 ms/hour at 100 ppm) is not. The
  counters that drive it are also the diagnostic surface for Scenario G, so they earn their place
  twice.
- The loop must not allocate — a GC pause is an audible glitch. Written down as a rule in
  [audio-pipeline.md](../audio-pipeline.md) and enforced by review, since nothing else will.
- If a device refuses 48 kHz, the fallback must be explicit and logged. Silently letting the two ends
  differ would insert a resampler and hide it.

## Alternatives considered

**16 kHz mono** ("it's only voice"). Would save perhaps 0.1% CPU while making the room sound like a
telephone. Brief §4 asks for natural environmental sound, and for a baby monitor the informative
sounds are often not speech.

**Stereo.** Duplicates one microphone's data. No information gained, double the copying.

**Float PCM.** Discarded by the A2DP encoder. Pure cost.

**Buffers at the platform minimum.** Would save 20–60 ms against a term dominated by the earbud,
while trading away exactly the reliability that brief §4 ranks first.

**Adaptive resampling for drift.** The correct long-term answer and a poor first one: a
control-loop plus a resampler, to solve a problem whose magnitude has not been measured yet.
Revisit if [H7](../feasibility.md) shows the coarse correction is audible.
