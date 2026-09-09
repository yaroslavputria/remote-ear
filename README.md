# RemoteEar

Turn an Android phone into a remote microphone.

Leave the phone in a room near a child, keep one Bluetooth earbud with you, and hear what the
phone's **built-in microphone** picks up. No second phone, no Wi-Fi, no backend, no account.

```text
        CHILD
          |
   +--------------+
   | Android phone|
   |  microphone  |
   +------+-------+
          |  local audio only
          v
    Bluetooth A2DP / LE Audio
          |
          v
    (o) headphones  ->  USER
```

The earbud's microphone is **not** the input. RemoteEar captures the phone's own microphone and
plays it out to the headphones.

## Status

**Pre-implementation.** This repository currently contains planning documents only — no application
code and no Gradle project yet. The product brief has been turned into a feasibility assessment,
architecture decisions, a scoped MVP, a prototype plan, a test matrix, and a ranked risk list.

- **Stack (decided):** native Kotlin + Jetpack Compose. See [ADR-0001](docs/adr/0001-native-kotlin-over-react-native.md).
- **Platform (decided):** `minSdk` 29 (Android 10), `targetSdk` 36. See [ADR-0002](docs/adr/0002-min-and-target-sdk.md).
- **Next step:** Phase 2 of the [implementation plan](docs/implementation-plan.md) — a throwaway
  prototype whose only job is to answer *"can I hear the room through my earbud?"*

**Build:** not yet.

## Documentation

Start at [docs/README.md](docs/README.md).

| Document | What it answers |
|---|---|
| [docs/feasibility.md](docs/feasibility.md) | Is this actually possible on modern Android, and with which APIs? |
| [docs/audio-pipeline.md](docs/audio-pipeline.md) | The recommended capture-to-playback design |
| [docs/android-constraints.md](docs/android-constraints.md) | Permissions, foreground service, background execution, Bluetooth routing |
| [docs/mvp-scope.md](docs/mvp-scope.md) | Must have / should have / later / out of scope |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phased steps, each with a gate |
| [docs/test-matrix.md](docs/test-matrix.md) | Devices, headphones, and the scenarios A–G |
| [docs/risks.md](docs/risks.md) | Ranked technical and product risks |
| [docs/privacy.md](docs/privacy.md) | The privacy invariants and how they are enforced |
| [docs/adr/](docs/adr/) | Architecture Decision Records |

## Privacy

Audio never leaves the phone. There is no cloud, no server, no account, and no audio storage. The
app will ship **without the `INTERNET` permission**, which makes that promise verifiable from the
manifest rather than merely stated. See [docs/privacy.md](docs/privacy.md).

## License

[MIT](LICENSE)
