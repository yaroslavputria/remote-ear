# ADR-0008 — Defer the sleep-sound feature behind an ordered three-question spike

- **Status:** Accepted
- **Date:** 2026-09-09

## Context

Brief §23 proposes a genuinely valuable feature: the phone plays white noise through **its own
speaker** while simultaneously monitoring the room through **its own microphone**, with the
phone-generated noise cancelled out of the monitored feed. It would turn RemoteEar into a combined
baby monitor and sleep-sound machine.

The brief identifies the hard part as cancellation, and reasons well about it: because the app
generates the signal, it has a perfect reference for acoustic echo cancellation.

**There is a blocker upstream of that, which the brief does not anticipate.**

While a Bluetooth sink is connected, Android routes media output to it. Playing white noise from the
**speaker** while monitoring streams to the **earbud** therefore requires **two simultaneous,
differently-routed output streams**. The only sanctioned lever is
`setPreferredDevice(TYPE_BUILTIN_SPEAKER)` on a second `AudioTrack` — and a preferred device is a
request that OEM audio policy may decline. Whether concurrent split routing works is device
dependent and, as far as desk research goes, unverified.

If it does not work, there is nothing to cancel, and every clever cancellation idea is moot.

Separately, the obvious cancellation tool is unavailable to us. `AcousticEchoCanceler` is documented
as reliable on the `VOICE_COMMUNICATION` capture path, which
[ADR-0004](0004-media-path-only.md) forbids — and forbids for good reason: taking that path would
hand the input to the earbud's microphone and break the core product.

## Decision

**The sleep-sound feature is post-MVP and gated behind a spike that answers three questions in this
order.** Each question can disqualify the next, so asking them out of order wastes the most
expensive work first.

**(a) Can two media streams route to the speaker and to A2DP concurrently?**
A second `AudioTrack` with `setPreferredDevice(TYPE_BUILTIN_SPEAKER)`, then `getRoutedDevice()` to
see whether the policy honoured it, across the device matrix. Cheap — perhaps an afternoon. **If no,
the feature as specified is not achievable and (b) and (c) do not matter.**

**(b) Is any platform echo cancellation usable off the communication path?**
Test whether `AcousticEchoCanceler.isAvailable()` and an attached effect do anything useful for an
`AudioSource.MIC` stream against a `USAGE_MEDIA` speaker output. Expected answer: no. Verify rather
than assume, because a yes would save a great deal of work.

**(c) Is known-spectrum suppression sufficient instead of true AEC?**
The strongest option, and the one worth investigating properly. RemoteEar *generates* the noise, so
unlike general echo cancellation:

- the interfering signal is **stationary** — white noise has a flat, constant spectrum,
- its spectrum is **known exactly, a priori**, with no adaptation needed to learn it,
- only the room's transfer function scales it, which a short calibration could estimate.

Spectral subtraction or an adaptive Wiener filter against a known stationary spectrum is a far
smaller problem than cancelling arbitrary audio, and needs no sample-aligned reference — which
matters, because sample alignment across two independently-clocked streams is itself hard
([ADR-0006](0006-audio-format-and-buffering.md)).

**Prerequisite:** the spike does not start before the Phase 6 gate in the
[implementation plan](../implementation-plan.md). Brief §23 agrees: *"First prove phone microphone →
Bluetooth headphones."*

## Consequences

- The MVP stays the size brief §22 demands.
- No MVP decision is bent to accommodate a deferred feature. In particular, ADR-0004 is not weakened
  to enable platform AEC — the temptation exists, and this ADR names it so it can be refused.
- The architecture keeps the feature possible: every frame passes through one place in the pipeline
  ([audio-pipeline.md](../audio-pipeline.md)), which is where suppression would go, and the white
  noise is to be generated locally rather than played from a file, preserving the exact-reference
  advantage the brief identifies.
- **The feature may prove impossible as specified**, on question (a), through no fault of the
  implementation. Recorded as [risk R11](../risks.md). Honest partial fallbacks exist and should be
  offered rather than hidden:
  - white noise while *not* monitoring (a plain sleep-sound machine),
  - monitoring with the noise audible in the feed — the parent hears what the child hears, which
    some users may simply accept,
  - a recommendation to use a separate noise source, with RemoteEar doing only what it does well.
- If (a) succeeds and (c) proves adequate, the result is a small, local, dependency-free DSP block —
  consistent with [privacy.md](../privacy.md) and needing no new permission.

## Alternatives considered

**Build it into the MVP.** Rejected by brief §23 itself, and it would put an unverified routing
assumption on the critical path of a product that does not otherwise need it.

**Use `VOICE_COMMUNICATION` capture to get platform AEC.** The obvious shortcut, and it breaks
brief §6: the earbud microphone becomes the input. Rejected by
[ADR-0004](0004-media-path-only.md).

**Port a full software AEC (WebRTC AEC3, speexdsp).** The general solution, and disproportionate: an
NDK dependency and sample-aligned full-duplex plumbing, to cancel a signal we already know exactly.
Worth revisiting only if (c) fails and (a) succeeded — and note that this is also the scenario in
which [ADR-0003](0003-audiorecord-audiotrack-for-mvp.md)'s deferral of Oboe should be reopened,
since sample-aligned full duplex is a genuine Oboe strength.

**Bundle audio files instead of generating noise.** Simpler for a prototype, as the brief notes, but
it discards the exact-reference property that makes (c) plausible. Generate locally.
