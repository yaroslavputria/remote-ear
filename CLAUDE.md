# RemoteEar

An Android phone used as a remote microphone: capture the phone's **built-in** microphone and play it
to **Bluetooth headphones** the user carries into another room. Local only — no network, no backend,
no storage.

**Current state: planning documents only.** There is no application code and no Gradle project yet.
Start at [docs/README.md](docs/README.md); the next step is Phase 1 of
[docs/implementation-plan.md](docs/implementation-plan.md).

## Hard invariants

Violating any of these is a bug, not a style preference.

1. **Never touch the communication audio path.** No `startBluetoothSco()`, `setMode(MODE_IN_*)`,
   `setCommunicationDevice()`, `setSpeakerphoneOn()`, `USAGE_VOICE_COMMUNICATION`, or
   `AudioSource.VOICE_COMMUNICATION`. It hands the input to the earbud's microphone and collapses
   A2DP to narrowband — the exact thing this product exists to avoid.
   → [ADR-0004](docs/adr/0004-media-path-only.md)
2. **`TYPE_BLUETOOTH_SCO` on either end is a hard error**, never a degraded mode. Assert
   `getRoutedDevice()` after starting the streams; a preferred device is a request, not a guarantee.
3. **No `INTERNET` permission, ever.** Its absence is what makes "audio never leaves the phone"
   verifiable. No analytics or crash-reporting SDK either.
   → [ADR-0007](docs/adr/0007-minimal-permission-set.md)
4. **Audio samples never reach a file, database, cache, or log.** Frame counters yes; frame contents
   no.
5. **No new permission without an ADR.** The set is four
   ([ADR-0007](docs/adr/0007-minimal-permission-set.md)). Needing `MODIFY_AUDIO_SETTINGS` or
   `INTERNET` is a signal that something has gone architecturally wrong.
6. **The realtime loop runs on a dedicated `Thread` at `THREAD_PRIORITY_URGENT_AUDIO`, not on a
   coroutine dispatcher**, and allocates nothing inside the loop. A GC pause is an audible glitch.
   → [audio-pipeline.md](docs/audio-pipeline.md)
7. **A paused monitor must look paused.** `Paused` carries a reason and is shown in both the UI and
   the notification. A user believing they are listening when they are not is the worst failure this
   product has.

## Decisions live in ADRs

[docs/adr/](docs/adr/) records the decisions with their alternatives and consequences. Read the
relevant one before proposing an architectural change. To change a decision, **write a new ADR that
supersedes it** — do not silently deviate, and do not edit an accepted ADR into a different
decision.

Unverified claims in the docs are marked *(measure)* or *(unverified)*. Do not launder them into
facts; several are load-bearing and the test matrix exists to settle them.

## Stack, once code lands

Kotlin · Jetpack Compose · Gradle KTS with a version catalog · `minSdk 29` / `targetSdk 36` · a
single `:app` module.

**Project overrides of the general Android house defaults** — these apply here regardless of what the
`android-dev` skill recommends by default:

- **No Hilt or any DI framework.** This app is a handful of classes; construct them by hand.
- **No Retrofit, no Room, no Coil.** There is no network, no database, and no remote image.
- **No third-party runtime dependencies** beyond AndroidX/Compose. In a microphone app, every
  dependency is a claim the user cannot audit — see [docs/privacy.md](docs/privacy.md).
- Coroutines and `StateFlow` are correct for lifecycle, state, and UI. They are **not** correct for
  the audio loop (invariant 6).

## Testing

**The emulator cannot verify anything this project is uncertain about** — it has no Bluetooth audio.
Routing, latency, profile behaviour, battery, and OEM process killing are all physical-device work.
The emulator is fine for Compose layout and permission plumbing.

The scenario matrix and the `adb` recipes are in [docs/test-matrix.md](docs/test-matrix.md) and the
`remote-ear-device-test` skill. Record results in `docs/test-runs/`, including what was *not* tested
— blank cells read as passes months later.

## Skills

| Skill | Use for |
|---|---|
| `remote-ear-audio` | Any work on the capture/playback pipeline, routing, or the foreground service |
| `remote-ear-device-test` | Running the scenario matrix and gathering evidence on a device |
| `android-dev`, `android-debugging`, `android-cli`, `kotlin-coroutines` | General Android work — subject to the overrides above |

## Git

- **Commit directly to `main`.** Small, logical commits.
- **Never `git push` without explicit approval**, every time. Prior approval does not carry over.
- **No `Co-Authored-By` trailer and no AI attribution** — not in commit messages, source headers,
  `LICENSE`, `README`, or docs. The author is Yaroslav Putria.
- Conventional-commit prefixes (`docs:`, `feat:`, `fix:`, `chore:`).
