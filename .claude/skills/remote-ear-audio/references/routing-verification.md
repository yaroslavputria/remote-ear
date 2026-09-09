# Proving the routing, rather than assuming it

RemoteEar's central failure mode is **inaudible**: audio arrives, sounds fine, and comes from the
wrong microphone over the wrong profile. You cannot hear a routing bug — you have to check for it.

## Why a check is needed at all

`setPreferredDevice()` sets a *preference*. The audio policy — including OEM modifications to it —
decides the actual route. So the code that requests `TYPE_BUILTIN_MIC` and the code that *gets*
`TYPE_BUILTIN_MIC` are not the same code, and only one of them is verifiable.

## In-app assertion (required, not optional)

Immediately after `startRecording()` and `play()`:

```kotlin
val inType  = record.routedDevice?.type
val outType = track.routedDevice?.type

// Log at start of every session — this is the evidence trail.
Log.i(TAG, "routing in=$inType out=$outType rate=${record.sampleRate} " +
           "recBuf=$recordBufferBytes trkBuf=$trackBufferBytes")
```

| Expectation | Value |
|---|---|
| `inType` | `TYPE_BUILTIN_MIC` (15) |
| `outType` | `TYPE_BLUETOOTH_A2DP` (8) or `TYPE_BLE_HEADSET` (26) |
| Hard failure | `TYPE_BLUETOOTH_SCO` (7) on **either** end |

**`TYPE_BLUETOOTH_SCO` is a hard error, not a degraded mode.** On the input it means the earbud's
microphone is the source — the phone has stopped being the listening device, which is the exact
requirement brief §6 states. Fail loudly with the observed types, tell the user something is wrong,
and do not play on.

A `null` `routedDevice` means the stream is not running yet. Assert after start, not before.

## What a silent SCO fallback looks like

Signals that the link has collapsed to the hands-free profile, in rough order of how early you see
them:

- `routedDevice.type` is `TYPE_BLUETOOTH_SCO` on either end. **The definitive check.**
- The negotiated sample rate is 8000 or 16000 where 48000 was requested.
- Audio sounds narrowband — "phone call", not "room".
- Speaking near the *earbud* is audible in the monitor, while speaking near the *phone* is not. This
  is the unambiguous listening test, and worth doing once per new device: **walk away from the phone
  and talk. If you can still hear yourself, the wrong microphone is live.**
- `dumpsys audio` shows a communication-mode entry, or an SCO device as the active input.

## adb verification

Recipes for the whole scenario matrix are in the `remote-ear-device-test` skill. The routing-specific
ones:

```bash
# Active routing, audio mode, focus stack, connected devices.
adb shell dumpsys audio > audio.txt

# What the audio system thinks the mode is — expect NORMAL (0), never IN_COMMUNICATION (3).
grep -iE "mode|MODE_" audio.txt

# Active input/output device types and the current focus owner.
grep -iE "bluetooth|a2dp|sco|ble|input device|output device|focus" audio.txt

# Which Bluetooth profiles are actually connected — A2DP should be, HEADSET/HFP should not
# be in use while monitoring.
adb shell dumpsys bluetooth_manager | grep -iE "profile|a2dp|headset|connected|le audio"

# Track and thread state, including underrun counts.
adb shell dumpsys media.audio_flinger | grep -iE "underrun|sample rate|format|channel|thread"
```

`dumpsys audio` output layout varies by Android version and OEM, so grep rather than expecting a
fixed shape — and when in doubt, trust the in-app `routedDevice` values over your reading of the
dump.

## Recording the evidence

The routing block is the **first** thing filled in on every test run, before any scenario, in
[`docs/test-runs/TEMPLATE.md`](../../../../docs/test-runs/TEMPLATE.md). "It sounded fine" is not a
routing result.

Capture per device and per pair of headphones — this is not a property of the app, it is a property
of the app *on that combination*, which is why the matrix has two axes.

## LE Audio deserves extra scrutiny

*(unverified)* LE Audio is bidirectional by design and organised around audio contexts. A stack may
engage the earbud microphone or reconfigure media quality when a capture stream is active — **without
the app calling anything on the forbidden list**. ADR-0004 compliance cannot prevent this; the
`routedDevice` assertion is what detects it.

On LE Audio hardware, additionally check:

- whether `outType` is `TYPE_BLE_HEADSET` and stays that way once capture starts,
- whether the walk-away-and-talk test still proves the built-in microphone is the source,
- whether audio quality changes at the moment monitoring starts.

Tracked as [risk R4](../../../../docs/risks.md) and hypothesis
[H2](../../../../docs/feasibility.md).
