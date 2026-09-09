# Architecture Decision Records

Each ADR records one decision: what forced it, what was chosen, what follows — including the parts
that are worse — and what else was considered.

**Decisions are append-only.** To change one, write a new ADR that supersedes it and mark the old one
`Superseded by`. Do not edit an accepted decision into a different decision; the record of what we
believed and why is the point.

Start from [`TEMPLATE.md`](TEMPLATE.md), or run `/adr <title>`.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-native-kotlin-over-react-native.md) | Native Kotlin + Compose, not React Native | Accepted |
| [0002](0002-min-and-target-sdk.md) | `minSdk 29`, `targetSdk`/`compileSdk 36` | Accepted |
| [0003](0003-audiorecord-audiotrack-for-mvp.md) | SDK `AudioRecord`/`AudioTrack`; Oboe deferred | Accepted |
| [0004](0004-media-path-only.md) | **Media path only — never Bluetooth SCO** | Accepted |
| [0005](0005-foreground-service-hosts-monitoring.md) | `microphone` foreground service hosts monitoring and stays alive while paused | Accepted |
| [0006](0006-audio-format-and-buffering.md) | 48 kHz mono PCM 16-bit, 2× buffers, coarse drift correction | Accepted |
| [0007](0007-minimal-permission-set.md) | Four permissions, and no `INTERNET` | Accepted |
| [0008](0008-sleep-sound-deferred.md) | Sleep sound deferred behind an ordered spike | Accepted |

## The one that matters most

[**ADR-0004**](0004-media-path-only.md). It is the constraint the audio design, the permission set,
and the phone-call behaviour all follow from — and the one a plausible-looking change is most likely
to violate.
