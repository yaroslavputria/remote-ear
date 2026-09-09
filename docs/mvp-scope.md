# MVP scope

> Deliverable 5 of the project brief (§21).
>
> Brief §22 is the standard every item here is measured against: *"Do not over-engineer this
> project. The core product is intentionally tiny."*

The whole MVP is one sentence: **put the phone near the child, put the earbud in your ear, hear what
is happening.** Anything that does not serve that sentence is below the line.

## Must have

Non-negotiable. The app is not shippable without all of these.

| # | Requirement | Notes |
|---|---|---|
| M1 | Request microphone permission with a rationale shown first | Brief §4.1; also satisfies Play's prominent-disclosure expectation |
| M2 | Detect whether a usable Bluetooth audio output is connected | Brief §4.2. Via `AudioManager.getDevices()`; needs no Bluetooth permission |
| M3 | Capture the phone's built-in microphone | Pinned to `TYPE_BUILTIN_MIC`, never the headset mic |
| M4 | Play it to the connected Bluetooth headphones | Media path only ([ADR-0004](adr/0004-media-path-only.md)) |
| M5 | Keep working when the app is not in the foreground | Brief §4.5 |
| M6 | Keep working when the screen is locked | Brief §4.6. Foreground service of type `microphone` |
| M7 | Ongoing notification showing live state, with a `Stop` action | Platform requirement and the only status surface once the phone is in another room |
| M8 | One clear Start/Stop control | Brief §4.7 |
| M9 | Status for: microphone permission, monitoring state, Bluetooth availability, errors | Brief §4.8, exactly these four |
| M10 | Output volume slider | Brief §10. `AudioTrack.setVolume()` |
| M11 | Pause — visibly, with a stated reason — on audio focus loss, phone call, or Bluetooth loss | Brief §16. A silent stop is the worst bug this product can have |
| M12 | Assert actual routing after start and fail loudly if it is wrong | If `TYPE_BLUETOOTH_SCO` appears, the wrong microphone is live |
| M13 | Tell the user plainly that audio is processed locally and never uploaded | Brief §12 |
| M14 | Ship with no `INTERNET` permission | Makes M13 verifiable rather than merely stated |

M12 and M14 are additions to the brief's own list. M12 because a preferred device is only a request
and the failure is inaudible-until-it-isn't; M14 because it converts the central privacy promise
into something a sceptical user can check.

## Should have

Wanted in the first release if the must-haves land cleanly. None of these block a ship.

| # | Requirement | Why it is not a must |
|---|---|---|
| S1 | Auto-resume when the headphones reconnect | Brief §16 explicitly allows deferring this if unreliable. Possible only because the service stays alive while paused ([ADR-0005](adr/0005-foreground-service-hosts-monitoring.md)) |
| S2 | Underrun, drift and lag counters, with periodic log summaries | The diagnostic surface for Scenario G. Needed to *understand* the product, not to run it |
| S3 | `MIC` versus `UNPROCESSED` input toggle | Resolves [H4](feasibility.md) on real hardware. May become a must if `MIC` processing gates quiet room sound |
| S4 | Software microphone gain with a limiter | Genuinely useful for hearing a quiet room, but `setVolume()` covers the basic case and gain risks clipping |
| S5 | Detect a session that ended without the user stopping it, and say so afterwards | The honest mitigation for OEM process kills ([R1](risks.md)) |
| S6 | Surface "microphone taken by another app" as a distinct state | Better than a generic error, but the generic error is not wrong |

## Later

Deliberately deferred. The architecture must not preclude them, which mostly means: keep the
pipeline a single place where every frame passes through.

| # | Feature | Prerequisite |
|---|---|---|
| L1 | Sleep sound / white noise through the phone speaker | A spike answering three questions in order — see [ADR-0008](adr/0008-sleep-sound-deferred.md). Brief §23 |
| L2 | Sound-activated playback (silence stays silent) | The continuous monitor proven reliable first. Brief §11 |
| L3 | Noise gate, high/low-pass filtering, voice enhancement | Same. Brief §11 |
| L4 | Pink/brown noise | L1 working |
| L5 | Reduced processing while quiet, to save battery | Real battery numbers from Scenario G. Optimising before measuring is guessing |
| L6 | Wired-headphone or speaker output | Trivial to add, outside the stated use case, and would need care not to create a feedback loop |

Brief §11 is explicit that these wait "until the basic audio monitor is proven reliable". L2 is
also the one that most tempts premature work: brief §10 says outright *"Do NOT initially implement
automatic sound detection."*

## Explicitly out of scope

Not "later" — **not part of this product**. From brief §3 and §12:

accounts · backend · cloud · Wi-Fi or network streaming · second-device mode · audio recording or
storage · AI · speech recognition · baby-cry classification · analytics involving audio · social
features · any permission beyond the four in [android-constraints.md](android-constraints.md) ·
complicated onboarding

Two of these deserve a note, because they are the ones a well-meaning future change would reach for:

- **Cry detection** (brief §11) is listed under future features there, but it is out of scope as a
  *product direction* for the MVP and interacts badly with the store-listing framing: an app that
  claims to detect a crying child is making a safety promise. Any future work here needs its own ADR
  and its own honesty about false negatives.
- **Crash reporting and analytics SDKs.** Standard practice for a normal app; a privacy contradiction
  for this one. A third-party SDK in a microphone app is a claim the user cannot verify. Excluded by
  [ADR-0007](adr/0007-minimal-permission-set.md).

## The one test that matters

From brief §18, Phase 2:

> **Can I put the phone in another room and reliably hear its microphone through my Bluetooth
> earbud?**

Everything in *Must have* exists to make the answer yes and to make it stay yes for several hours.
Everything else waits.
