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

**The core use case is proven on hardware.** Phase 2's prototype was run on a OnePlus CPH2399
(Android 14) with real Bluetooth earbuds, and the answer to the question the project rests on is
yes: the phone's built-in microphone was clearly audible in the earbud from another room, routed
`BUILTIN_MIC → BLUETOOTH_A2DP` with no SCO anywhere.
See the [test run](docs/test-runs/2026-09-09-oneplus-cph2399.md).

This is a working prototype, **not yet a product**: there is no foreground service, so monitoring
stops when the app is backgrounded.

- **Stack:** native Kotlin + Jetpack Compose ([ADR-0001](docs/adr/0001-native-kotlin-over-react-native.md))
- **Platform:** `minSdk` 29 (Android 10), `targetSdk` 36 ([ADR-0002](docs/adr/0002-min-and-target-sdk.md))
- **Next step:** Phase 3 of the [implementation plan](docs/implementation-plan.md) — move the
  pipeline into a `microphone` foreground service so it survives backgrounding and screen lock

## Build

Requires JDK 17+ and the Android SDK (Platform 36, Build-Tools 36.0.0). No admin rights are needed
to install either — see [dev-setup.md](docs/dev-setup.md).

```bash
./gradlew :app:installDebug
adb shell am start -n com.yputria.remoteear/.MainActivity
adb logcat -s RemoteEar:V          # routing, buffer sizes, counters
bash tools/check-invariants.sh     # enforces ADR-0004 and ADR-0007
```

There is no emulator target. The emulator has no Bluetooth audio and can verify none of the things
this project is uncertain about.

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
