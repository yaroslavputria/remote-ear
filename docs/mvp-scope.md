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
| M10 | Output volume | Brief §10 — satisfied by the **phone media volume**, which the app displays rather than duplicating. See below |
| M11 | Pause — visibly, with a stated reason — on audio focus loss, phone call, or Bluetooth loss | Brief §16. A silent stop is the worst bug this product can have |
| M12 | Assert actual routing after start and fail loudly if it is wrong | If `TYPE_BLUETOOTH_SCO` appears, the wrong microphone is live |
| M13 | Tell the user plainly that audio is processed locally and never uploaded | Brief §12 |
| M14 | Ship with no `INTERNET` permission | Makes M13 verifiable rather than merely stated |
| M15 | Detect microphone silencing via `isClientSilenced()` and pause with a stated reason | *(verified)* Losing the microphone delivers **silence, not an error** — see below |

M12, M14 and M15 are additions to the brief's own list. M12 because a preferred device is only a
request and the failure is inaudible-until-it-isn't; M14 because it converts the central privacy
promise into something a sceptical user can check.

**M15 was promoted from "should have" (S6) by the Phase 1 verification.** Android delivers buffers of
zeros — not an error — when another app wins the microphone or the user flips the privacy toggle. In
a monitor, silence is indistinguishable from a quiet room, so without this the app confidently
reports "Monitoring" while relaying nothing. That is the same class of failure as
[risk R1](risks.md), which is ranked first in the project, so it cannot sit below the line. The
platform provides `AudioRecord.registerAudioRecordingCallback()` +
`AudioRecordingConfiguration.isClientSilenced()` at API 29, our `minSdk` floor, which makes it cheap
as well as necessary.

**M10 has no app-side slider, deliberately.** Playback rides `STREAM_MUSIC`, so the phone volume
buttons and the earbud own controls already set loudness — and the earbud is the only control the
user can reach once the phone is in another room. An app slider would be worse than redundant:
`AudioTrack.setVolume()` caps at 1.0, so it could only ever *attenuate*, never help with the
"too quiet" complaint this product actually gets, while a forgotten setting would silently cap how
loud the earbud could get. The app therefore **shows** the phone volume and offers no second
control. Making the room *louder* than it is needs gain above unity plus a limiter — S4, a different
mechanism.

## Should have

Wanted in the first release if the must-haves land cleanly. None of these block a ship.

| # | Requirement | Why it is not a must |
|---|---|---|
| S1 | Auto-resume when the headphones reconnect | Brief §16 explicitly allows deferring this if unreliable. Possible only because the service stays alive while paused ([ADR-0005](adr/0005-foreground-service-hosts-monitoring.md)) |
| S2 | Underrun, drift and lag counters, with periodic log summaries | The diagnostic surface for Scenario G. Needed to *understand* the product, not to run it |
| S3 | `MIC` versus `UNPROCESSED` input toggle | Resolves [H4](feasibility.md) on real hardware. May become a must if `MIC` processing gates quiet room sound |
| S4 | Software microphone gain with a limiter | Genuinely useful for hearing a quiet room, but `setVolume()` covers the basic case and gain risks clipping |
| S5 | Detect a session that ended without the user stopping it, and say so afterwards | The honest mitigation for OEM process kills ([R1](risks.md)) |
| ~~S6~~ | ~~Surface "microphone taken by another app" as a distinct state~~ | **Promoted to M15** — the failure turned out to be silent, so a generic error was not merely coarse, it was absent |
| S7 | **Noise cancellation as a single level control** — *added 2026-09-10, shipped early in Phase 3* | See below |

### S7 — one control, two mechanisms underneath

Android’s `NoiseSuppressor` has **no strength parameter** — unlike `BassBoost` or `Virtualizer` it
is enabled or disabled and nothing in between — so anything adjustable had to be implemented in our
own loop. Rather than exposing that split to the user as two controls, **one slider drives both**:
at the minimum everything is off; above it the filter scales continuously and the platform
suppressor is simply on.

- **Our half** is a one-pole high-pass, cutoff sweeping ~20–400 Hz. It attenuates the rumble people
  actually complain about (fans, traffic, air conditioning) and **cannot mute the room**: it changes
  the *tone* of what you hear, never *whether* you hear it. That property is why a high-pass was
  chosen over an adjustable **noise gate**, which would have re-created [risk R2](risks.md) and
  handed the user a dial to do it with. A gate would need its own ADR.
- **The platform half** *is* the R2 mechanism: tuned to isolate a near-field talker and discard
  ambient sound, when here the ambient sound is the signal. It is why high settings carry a caveat
  in the UI rather than being presented as better.

Neither replaces S4. If the goal is “hear the child more clearly”, **gain is the right lever and
suppression is the wrong one**. If measurement shows high settings degrade quiet-room audio, the
honest response is a lower ceiling, not a caveat buried in a settings screen.

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

Also out of scope, having been **investigated and rejected on 2026-09-10**:

- **Using one earbud as the microphone and the other as the speaker.** A TWS pair is a single
  Bluetooth endpoint, an earbud microphone is reachable only over HFP/SCO (which owns both
  directions), and the buds talk to the phone rather than to each other — so the phone would still
  have to be in the room. Three independent blockers; see
  [ADR-0004](adr/0004-media-path-only.md).
- **The reverse direction — earbud microphone out of the phone speaker.** Possible, but it requires
  the SCO path ADR-0004 forbids, yields narrowband mono, and inverts the product: the earbud has the
  worse microphone, a 4–6 hour battery, and would be left in a child’s room.

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
