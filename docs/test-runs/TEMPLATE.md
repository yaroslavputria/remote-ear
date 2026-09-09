# Test run — <YYYY-MM-DD>

Copy this file to `<YYYY-MM-DD>-<device>-<headphones>.md` and fill it in during the session, not
afterwards.

## Setup

| | |
|---|---|
| Device | e.g. Pixel 8, Android 15, build … |
| Headphones | e.g. Galaxy Buds2 Pro, LE Audio |
| Codec negotiated | SBC / AAC / aptX / LC3 / unknown |
| App build | commit hash |
| Input source | `MIC` / `UNPROCESSED` |

## Routing check (do this first)

| | Observed |
|---|---|
| `record.routedDevice.type` | expect `TYPE_BUILTIN_MIC` |
| `track.routedDevice.type` | expect `TYPE_BLUETOOTH_A2DP` or `TYPE_BLE_HEADSET` |
| Any `TYPE_BLUETOOTH_SCO` seen? | **must be no** |
| Negotiated sample rate / channels | |
| Record / track buffer bytes | |

> A `TYPE_BLUETOOTH_SCO` sighting is a hard failure even if the audio sounds fine — it means the
> earbud's microphone is the input. Stop and investigate.

## Scenarios

| # | Scenario | Result | Notes |
|---|---|---|---|
| A | Same room | pass / fail / not run | latency, quiet-room impression |
| B | Behind one wall | | dropouts, distance |
| C | Screen locked | | duration, service survived? |
| D | Backgrounded | | state correct on return? |
| E | Bluetooth disconnect | | detect time, resume time, both disconnect methods |
| F | Incoming call | | focus codes, read() behaviour, call unaffected? |
| G | Multi-hour | | see below |

**Record "not run" explicitly.** Blank cells read as passes later.

## Latency

| | |
|---|---|
| Method | clap test / subjective |
| Measured | … ms |
| Subjective | usable / noticeable / annoying |

## Quiet-room behaviour

Does a quiet room sound like a quiet room, or is it gated to digital silence? Note anything that
sounds like AGC pumping when a sound starts.

## Long run (Scenario G)

| | |
|---|---|
| Duration | |
| Battery start → end | …% → …% (…%/hour) |
| Survived without user stopping it? | |
| Underruns | |
| Drift corrections applied | |
| Audio quality at end vs start | |
| Peak temperature | |
| CPU / memory | |

## Failures and surprises

Anything unexpected, including things that turned out fine. Note the device and OEM behaviour
specifically — this is the raw material for [risks.md](../risks.md).

## Follow-ups

- [ ] …
