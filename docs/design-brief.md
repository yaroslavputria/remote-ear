# Design brief — RemoteEar

> **Input for design work.** Self-contained: a designer should not need to read the rest of `docs/`.
> Everything here is derived from the product brief §9/§16 and the decisions in [adr/](adr/).
>
> **Scheduling:** the UI is built in **Phase 4** of the [implementation plan](implementation-plan.md),
> but design does **not** need to wait for the Phase 2 audio gate. Everything design depends on —
> the state set, the status items, the notification's role — is already settled. This is a parallel
> track and can start now.

## What the product is

An Android phone used as a **remote microphone**. You leave the phone in a room near a sleeping
child, carry one Bluetooth earbud with you, and hear that room. Everything is local: no cloud, no
account, no recording, no internet permission at all.

One screen. No onboarding, no settings, no second screen.

## The context of use should drive every decision

This is not a general-purpose app being used at a desk. It is used:

- **At night, in a dark room**, by a tired parent, often one-handed, with an earbud already in.
- **For about ten seconds.** The user opens the app, taps START, and *walks away leaving the phone
  behind*. They return later and tap STOP.
- **Then not at all** — for hours. During that time the **notification is the only surface that
  exists**, because the phone is in another room and the screen is off.

Two consequences that matter more than any visual choice:

1. **A bright screen in a child's bedroom is a product defect.** Dark mode is not a preference here,
   it is the primary appearance. Consider how dim the monitoring state can go while staying legible.
2. **The notification is a first-class design surface**, not an afterthought. Design it deliberately,
   with the same care as the screen.

## The hardest problem: a paused monitor must *look* paused

The worst failure this product can have is **a user believing they are listening when they are
not**. Bluetooth drops, a call arrives, another app steals the microphone — and if the UI still
reads "Monitoring", the user has been given a false sense of safety about their child.

So the pause states are the design problem, not the happy path.

A coloured dot is **not sufficient**: it fails for colour-blind users, fails in a dark room at a
glance, and fails when the phone is face-down in another room. Distinguish states by **at least two
channels** — colour *and* text, ideally plus shape or icon.

## States to design

| State | Meaning |
|---|---|
| `Idle` | Not monitoring. The default on open |
| `Starting` | Streams opening, routing being verified. Brief, but real |
| `Monitoring` | Working. Audio is flowing to the earbud |
| `Paused — headphones disconnected` | Bluetooth went away. Resumes by itself on reconnect |
| `Paused — phone call` | A call is in progress. Resumes when it ends |
| `Paused — audio interrupted` | Another app took audio focus |
| `Paused — microphone in use elsewhere` | Another app took the microphone |
| `Error` | Something is wrong and the user must act |

The five pause reasons are functionally different and each needs its own **microcopy**. The product
brief supplies one example of the tone wanted:

```text
Bluetooth headphones disconnected.

Monitoring is paused.
Reconnect headphones to continue.
```

Plain, calm, specific about what to do. Never alarming, never vague.

## Status: exactly four items

The brief specifies four, and the design should not add a fifth:

1. **Microphone permission** — granted or not
2. **Monitoring state** — the states above
3. **Bluetooth audio availability** — is a usable output connected
4. **Errors**

## Controls

- **"Listen" / "Stop listening"** — one primary control. *(Label decided 2026-09-10; the brief's
  sketch says START/STOP, but "Listen" says what the app does rather than what the software is
  doing.)* It is pressed in the dark, possibly half-asleep, so it wants a **large, unambiguous
  target**, and the stop state must not be confusable with the start state at a glance.
- **The control is disabled when it cannot work** — no microphone permission, or no headphones
  connected — and **says why** rather than sitting inert. Bluetooth presence is live, so it
  enables and disables as headphones come and go.
- **Volume** — the phone’s own media volume already controls loudness, from the phone buttons and
  from the earbud itself, because playback rides the media stream. The app therefore **shows** that
  level and offers only a *trim* beneath it. Design should make the phone volume look like the real
  control and the trim look secondary — a forgotten trim silently caps how loud the earbud can get.
- **Noise cancellation** — a single slider, like volume, **off at the minimum**. One control, even
  though two mechanisms sit underneath it (see [mvp-scope.md](mvp-scope.md) S7). High settings can
  hide quiet sounds, so the label needs to avoid implying that more is better.
- **Microphone permission request** — with a short rationale shown *before* the system dialog.

## Reference sketch from the product brief

Directional only — improve on it freely, but do not add to it.

```text
┌──────────────────────────────┐
│         RemoteEar            │
│                              │
│        Monitoring ●          │
│                              │
│       Bluetooth: ●           │
│       Microphone: ●          │
│                              │
│          [ STOP ]            │
│                              │
│         Volume               │
│       ───────●──────         │
└──────────────────────────────┘
```

## The notification

Needs its own design pass. It must carry:

- the **live** state, with the same wording as the screen — never a stale "Monitoring"
- a **Stop** action, so ending a session never requires walking back and reopening the app
- low-importance styling: visible, never making a sound or vibrating

## Trust and tone

Privacy is the product's backbone, so the design carries it:

- This exact sentence (or a designed equivalent) belongs on the screen:
  > Audio is processed on this phone only and played to your headphones. Nothing is recorded,
  > saved, or sent anywhere. RemoteEar has no internet access.
- **Tone: a calm utility, not a surveillance product.** No camera/CCTV visual language, no "security"
  framing, no imagery of a watched child.
- **Never imply detection or alerting.** The app plays a microphone to headphones. It does not
  detect crying and will not alert anyone — implying otherwise is a safety claim it cannot honour.

## Accessibility — functional here, not compliance

- **High contrast in dark conditions.** The screen is read in an unlit room.
- **Status dots need text or content descriptions.** Colour alone is not information.
- **Large touch targets**, comfortably beyond the 48 dp minimum for START/STOP.
- Must survive large font scales without truncating a pause reason — the reason is the safety
  message.

## Constraints

- **Jetpack Compose + Material 3.** Design in terms M3 can express.
- **No third-party dependencies** — no Lottie, no custom icon sets, no downloaded fonts. Material
  Icons and system fonts only. In a microphone app, every dependency is a claim the user cannot
  audit.
- Light **and** dark palettes, dark being primary.
- Android only. Phone form factor. Portrait.

## Explicitly out of scope

Onboarding flows · settings screens · accounts or sign-in · a sound-level meter or waveform (it
implies detection we deliberately do not do) · cry detection · sleep-sound/white-noise UI (a later
phase, gated on a feasibility spike) · second-device pairing · charts · widgets · tablet layouts.

## What would be most useful back

In rough order of value:

1. **Microcopy for every state**, especially the five pause reasons, plus the notification text.
   This is the highest-value deliverable — the wording *is* the safety feature.
2. **The one screen in each state**: Idle, Monitoring, each Paused variant, Error, and
   permission-not-granted.
3. **The notification** in Monitoring, Paused, and Error.
4. **Colour tokens** for light and dark, including the state colours, checked for colour-blind
   safety and for legibility at low screen brightness.
5. A note on how dim the Monitoring state can go while remaining readable at a glance in the dark.
