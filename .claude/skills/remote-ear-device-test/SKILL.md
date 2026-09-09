---
name: remote-ear-device-test
description: >
  How to verify RemoteEar on a physical Android device and gather evidence. Use when running or
  planning any of the scenarios A–G from the test matrix, measuring latency, battery, audio
  underruns or clock drift, forcing Doze, simulating Bluetooth disconnects or calls, checking audio
  routing on hardware, or when someone asks whether a change "works" on a device. Trigger on
  dumpsys audio, media.audio_flinger, batterystats, deviceidle, bluetooth_manager, scenario A-G,
  test matrix, long run, battery drain, latency measurement, screen locked, OEM kill, "test on
  device", "does it work". Also use when tempted to verify audio on an emulator — it cannot be done.
---

# Testing RemoteEar on a device

## The emulator cannot help

**The Android emulator has no Bluetooth audio.** It cannot verify routing, latency, profile
behaviour, codec differences, battery, clock drift, or OEM process killing — which is every open
question in this project.

The emulator is fine for Compose layout and permission-flow plumbing. For anything audio: **physical
device, real Bluetooth headphones**. If a task asks for audio verification and no device is
available, say so rather than substituting an emulator run.

Scenarios, devices, headphones and pass criteria: [docs/test-matrix.md](../../../docs/test-matrix.md).
Record results from [docs/test-runs/TEMPLATE.md](../../../docs/test-runs/TEMPLATE.md).

## Always start with routing

Before any scenario. Audio being audible proves nothing about *which microphone* produced it.

```bash
adb shell dumpsys audio > audio.txt
grep -iE "mode|bluetooth|a2dp|sco|ble|focus" audio.txt
adb shell dumpsys bluetooth_manager | grep -iE "profile|a2dp|headset|le audio|connected"
```

Plus the in-app `getRoutedDevice()` log line. Expect `TYPE_BUILTIN_MIC` in,
`TYPE_BLUETOOTH_A2DP`/`TYPE_BLE_HEADSET` out, and **never** `TYPE_BLUETOOTH_SCO`.

**The listening test that needs no tooling:** walk away from the phone and talk. If you still hear
yourself, the earbud's microphone is live and the product is broken regardless of what anything
sounds like.

Full detail: `remote-ear-audio` skill, `references/routing-verification.md`.

## Device basics

```bash
adb devices -l
adb shell getprop ro.build.version.release       # Android version
adb shell getprop ro.build.version.sdk           # API level
adb shell getprop ro.product.manufacturer        # OEM — matters for kill behaviour
adb shell getprop ro.product.model

# App logs only
adb logcat --pid=$(adb shell pidof -s com.example.remoteear)
```

`android-debugging` covers general logcat, ANR, and crash work. This skill covers only what is
specific to audio and to long-running monitoring.

## Audio internals

```bash
# Underruns, track/thread state, negotiated formats. The primary drift and glitch surface.
adb shell dumpsys media.audio_flinger > flinger.txt
grep -iE "underrun|sample rate|format|channel|thread|Fast" flinger.txt

# Is the service actually still in the foreground?
adb shell dumpsys activity services com.example.remoteear

# Which app currently holds the microphone.
adb shell dumpsys media.audio_policy | grep -iE "record|input|client"
```

For Scenario G, snapshot `media.audio_flinger` at the start and at the end and compare underrun
counts — the delta over hours is the drift signal.

## Screen off and lock (Scenarios C, D)

```bash
adb shell input keyevent 26        # power — toggles screen
adb shell input keyevent 82        # unlock (menu) if needed
adb shell dumpsys power | grep -iE "mScreenOn|Display Power|wake"
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME   # background the app
```

Screen off is **not** Doze. To actually test Doze:

```bash
adb shell dumpsys deviceidle force-idle     # force Doze
adb shell dumpsys deviceidle step           # advance the state machine
adb shell dumpsys deviceidle unforce        # back to normal
adb shell dumpsys deviceidle get deep       # current state
```

Note: `force-idle` may require the device to be unplugged from charging to take effect. Waiting for
natural Doze is not a test plan.

## Bluetooth disconnect (Scenario E)

Test **both** ways — the callback timing differs:

1. **Power the earbuds off** (or put them in the case).
2. **Walk out of range.** The real-world case, and slower to detect.

```bash
# Watch the audio system notice.
adb shell dumpsys audio | grep -iE "a2dp|bluetooth|ble"

# Force it from the host side if needed (this disables Bluetooth entirely — blunt, but reproducible).
adb shell svc bluetooth disable
adb shell svc bluetooth enable
```

Measure: time to detect, the state text shown, time to auto-resume on reconnect. `svc bluetooth
disable` is a harsher event than an earbud running out of battery, so do not let it substitute for
the two real methods.

## Incoming call (Scenario F)

**Needs a second phone.** The emulator's `gsm call` cannot help, because there is no Bluetooth audio
for it to interact with.

Capture: the audio-focus change codes the app observed, what `AudioRecord.read()` returned during the
call, whether the call itself was affected at all (it must not be), and whether monitoring resumed
afterwards.

```bash
adb shell dumpsys audio | grep -iA5 "focus"
adb shell dumpsys telephony.registry | grep -i "mCallState"
```

## Battery and the long run (Scenario G)

The scenario that finds what the others cannot. Run it on the **aggressive-killer OEM device
specifically** — a Pixel passing Scenario G says little about a Xiaomi.

```bash
# Reset counters, then unplug. Charging invalidates the measurement.
adb shell dumpsys batterystats --reset

# ... run for hours, phone unplugged, screen locked, in another room ...

adb shell dumpsys batterystats --charged com.example.remoteear > battery.txt
adb shell dumpsys battery                       # level, temperature
adb shell dumpsys procstats com.example.remoteear
adb shell top -b -n 1 | grep remoteear          # CPU, memory
adb shell cat /sys/class/thermal/thermal_zone0/temp   # path varies by device
```

Record: drain per hour, whether the process survived, underrun delta, drift corrections applied,
audio quality at the end versus the start, peak temperature.

**The pass criterion that matters most is negative:** no silent stop. Check that the process is still
alive and the service still foregrounded, and note whether the notification is still present.

```bash
adb shell pidof com.example.remoteear                          # empty output = it died
adb shell dumpsys activity services com.example.remoteear      # still foreground?
```

## Latency (H3)

Clap near the phone while recording the earbud output on a second device, then measure the offset
between the two transients in any audio editor. Repeat per headphone class — latency is more a
property of the headphones than of the phone.

Note the negotiated codec, because it explains most of the number:

```bash
adb shell dumpsys bluetooth_manager | grep -iE "codec|sbc|aac|aptx|lc3"
```

Subjective judgement ("does it feel laggy holding a conversation through it") is a legitimate signal
for this product and worth writing down alongside the measurement.

## Quiet-room behaviour (H4, risk R2)

Not a command — a listening test, and one of the two things that can make the product pointless.

In a genuinely quiet room (not an office), with the `MIC` / `UNPROCESSED` toggle:

- Does a quiet room sound like a quiet room, or like digital silence?
- Are small sounds — rustling, breathing, a distant voice — audible?
- Does the level "pump" when a sound starts, indicating AGC?

If `MIC` gates the room and `UNPROCESSED` does not, that promotes the toggle to a must-have and
brings software gain with it. Write down what you heard, in words.

## Recording results

One file per session: `docs/test-runs/<YYYY-MM-DD>-<device>-<headphones>.md`, from the template.

**Record "not run" explicitly.** Blank cells read as passes months later, and that is how a monitor
ships broken on a phone nobody happened to try. Write down the boring passes too — an unexplained
gap in the matrix is worse than a documented failure.
