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
| M11 | Pause — visibly, with a stated reason — on audio focus loss, phone call, or Bluetooth loss | Brief §16. A silent stop is the worst bug this product can have. *Built in Phase 5; Bluetooth loss verified on hardware, the other two not yet* |
| M12 | Assert actual routing after start and fail loudly if it is wrong | If `TYPE_BLUETOOTH_SCO` appears, the wrong microphone is live |
| M13 | Tell the user plainly that audio is processed locally and never uploaded | Brief §12 |
| M14 | Ship with no `INTERNET` permission | Makes M13 verifiable rather than merely stated |
| M15 | Detect microphone silencing via `isClientSilenced()` and pause with a stated reason | *(verified)* Losing the microphone delivers **silence, not an error** — see below |
| M16 | **Privacy policy and terms of use reachable inside the app** — *added 2026-09-10* | Play requires the privacy policy to be reachable in the app as well as the listing, and this product needs its "not a safety device" statement somewhere. See below |

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

### M16 — the two documents

They live at [`app/src/main/assets/legal/`](../app/src/main/assets/legal/) and are rendered in-app
from those exact files, reachable from an overflow menu on the wordmark row. **The text is not
copied into `strings.xml`**: the file a reader sees in this repository is the file the app displays,
so the privacy claim on screen is byte-identical to the one anyone can audit. An app whose central
promise is "nothing leaves this phone" should not keep three drifting copies of that sentence.

The **terms** matter more than legal hygiene here. This product is left in a room with a child, and
it can fail silently for at least six reasons outside its control — Bluetooth range, battery, OEM
process killing, a call, another app taking the microphone, an OS update. The document says plainly
that it is **not a baby monitor, medical device or safety device**, and that it must never be the
only means of supervising anyone. That is not boilerplate; it is the honest counterpart to
[risk R1](risks.md).

The contact address is filled in. **There is deliberately no governing-law clause**: naming a
jurisdiction would be a real legal choice, it protects the author only marginally for a free app
with no accounts and no data, and an invented one would be worse than none. Add one line if that
changes.

Play still wants the policy at a **public URL** in the store listing — the same file in this
repository can serve as that. The Data Safety declaration is in [privacy.md](privacy.md).

Neither document has been reviewed by a lawyer. They were written to be *accurate*, which is easy
for the privacy policy — the honest answer is "nothing is collected" — and less so for the terms.

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
| S1 | Auto-resume when the headphones reconnect — *shipped and verified 2026-09-10, ~5 s unattended* | Brief §16 explicitly allows deferring this if unreliable. Possible only because the service stays alive while paused ([ADR-0005](adr/0005-foreground-service-hosts-monitoring.md)) |
| S2 | Underrun, drift and lag counters, with periodic log summaries | The diagnostic surface for Scenario G. Needed to *understand* the product, not to run it |
| S3 | `MIC` versus `UNPROCESSED` input toggle | Resolves [H4](feasibility.md) on real hardware. May become a must if `MIC` processing gates quiet room sound |
| S4 | Software microphone gain with a limiter | Genuinely useful for hearing a quiet room, but `setVolume()` covers the basic case and gain risks clipping |
| S5 | Detect a session that ended without the user stopping it, and say so afterwards | The honest mitigation for OEM process kills ([R1](risks.md)) |
| ~~S6~~ | ~~Surface "microphone taken by another app" as a distinct state~~ | **Promoted to M15** — the failure turned out to be silent, so a generic error was not merely coarse, it was absent |
| S7 | **Noise cancellation as a single level control** — *added 2026-09-10, shipped early in Phase 3* | See below |
| S8 | **The listening screen dims itself after 20 s untouched** — *added 2026-09-10 from the design, shipped in Phase 4* | See below |

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

### S8 — the listening screen dims itself

The design proposed it and the brief had only asked for a *note* on how dim the screen could go. It
is worth having: the phone is left in a room where someone is asleep, and a bright screen there is a
cost the product imposes for nothing. After 20 s untouched the surface goes black, the state text
drops to ~5:1 and Stop to ~4.6:1, and everything below Stop stops being drawn — about a tenth of the
light. Any touch restores it, and that first touch is absorbed rather than passed through, so a blind
tap in the dark cannot hit Stop.

**State text and Stop never fade**, and a state change cancels the dim. A dimmed screen must still
answer "is it listening?" from a doorway — if dimming could hide a pause, it would be breaking
invariant 7 to save a little light.

**Its value depends on the screen timeout**, which is worth stating rather than discovering:
[Phase 3](implementation-plan.md) deliberately dropped `FLAG_KEEP_SCREEN_ON` because the service is
what keeps listening alive, so on a default phone the screen turns off entirely before 20 s of
dimming buys anything. This is for the user who has a long timeout, not a reason to hold the screen
on.

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
