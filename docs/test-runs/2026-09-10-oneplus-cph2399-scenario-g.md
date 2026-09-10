# Test run — 2026-09-10 — OnePlus CPH2399 — Scenario G (in progress)

| | |
|---|---|
| Device | OnePlus CPH2399 (`OP557AL1`), ColorOS, **Android 14 / API 34** |
| Headphones | **JBL WAVE FLEX** — the first run where the model is recorded, because the app now shows it |
| Build | `cbf1a48`, debug |
| Scenario | **G — several hours of monitoring** ([test-matrix.md](../test-matrix.md)) |
| Status | **Run 1 started 15:33. Not a result yet.** |

## Why this is two runs

The phone cannot be measured and charged at the same time, and unplugging it also takes `adb` away
— and with `adb` goes the per-minute time series, because ColorOS rotates our log lines out of the
ring buffer within a minute.

| | Run 1 — plugged in, `adb` attached | Run 2 — unplugged, unattended |
|---|---|---|
| Answers | drift, underruns, stalls, memory, thermals, no-silent-stop | battery drain, OEM process kill |
| Evidence | per-minute `SUMMARY` lines streamed to a file | battery % delta, `batterystats`, the app's own end-of-run counters |
| Hypotheses | [H7](../feasibility.md) | [H5, H6](../feasibility.md) |

The counters are cumulative and peak-tracking precisely so Run 2 still yields evidence from a single
snapshot at the end. What it cannot show is *when* — steady growth versus one jump.

## Run 1 — first two minutes

```text
15:34:25  SUMMARY t=1.0min frames in=2884800 out=2884800 drift=0 underruns=0
          read=18982/78572us write=1011/79045us corrections=drop:0,pad:0 peakBacklog=40ms
15:35:25  SUMMARY t=2.0min frames in=5781120 out=5781120 drift=0 underruns=0
          read=18616/78572us write=1368/79045us corrections=drop:0,pad:0 peakBacklog=40ms
```

| Reading | Value | Reading it |
|---|---|---|
| Underruns | **0** | The output has not starved |
| Drift corrections | **0 drops, 0 pads** | Nothing to correct yet, and — more usefully — **no false positives**: the drop threshold is not tripping on ordinary jitter |
| Peak backlog | **40 ms, unchanged** | Two frames, the expected steady state, and well under the 100 ms drop threshold. Flat over two minutes means the input is not backing up |
| `framesIn == framesOut` | exactly | No frames lost, none invented |
| Mean read block | ~19 ms | **The read is what paces the loop**, not the write — the opposite of the assumption behind the buffer sizing in [ADR-0009](../adr/0009-buffer-sizing-measured.md), and worth noting |
| Mean write block | 1.0–1.4 ms | The 215 ms A2DP track buffer is never close to full |
| **Max read / max write** | **78.6 ms / 79.0 ms** | **The one thing to watch.** See below |
| CPU | 16.6 % of one core | ~2 % of an eight-core phone |
| Memory | 81 MB PSS / 187 MB RSS | Ordinary for a Compose app; flat so far |
| Thermal status | `mStatus=0` on every sensor | No throttling. Battery 31.2 → 33.5 °C, but it is **charging at 100 %**, so that rise is not ours to claim |

### The 79 ms stall

Both maxima are ~79 ms and **both stopped changing before the first minute elapsed** — so this was a
single event, almost certainly during start-up, that stalled a read and the write after it. It has
not recurred in the minutes since.

It matters because 78 ms is most of the **80 ms record buffer** ([ADR-0009](../adr/0009-buffer-sizing-measured.md)):
a stall of that length while the buffer is already full is exactly how a frame gets lost. One at
start-up is harmless. **A pattern of them during the run is a defect**, which is why the maxima are
tracked at all — a mean over 700,000 frames would have hidden it completely.

## Not answered yet

- **Everything the scenario actually exists for**: hours, not minutes. No claim about hour four can
  be made from two minutes of data.
- **Battery** — impossible while plugged in. Needs Run 2.
- **OEM process killing** ([R1](../risks.md), ranked first) — needs hours with the screen off and the
  phone left alone.
- **Audible quality at hour four** — a human ear, not a counter.
- A second device. There is one phone.

## Protocol — Run 2, unplugged

1. Note the battery percentage. Unplug.
2. Tap **Listen**, screen off, leave the phone alone for as long as you can — overnight is the real
   test.
3. In the morning: **before touching anything**, read the screen. Three outcomes worth
   distinguishing:
   - **`Listening`** — it survived. Long-press the wordmark to reveal Diagnostics and photograph the
     counters line: elapsed, underruns, corrections, peak backlog, max block times.
   - **`Paused`** — it noticed something and said so. The reason is the finding.
   - **Nothing running, and a notice saying listening stopped on its own** — that is
     [R1](../risks.md) landing, and it is the single most important result this project can collect.
     ColorOS is exactly the OEM class where it is expected.
4. Note the battery percentage, then plug in and run
   `adb shell dumpsys batterystats --charged com.yputria.remoteear`.

For the earbuds: leaving them out of their case on a desk usually keeps A2DP alive, whereas the case
disconnects them — which would pause the run (correctly) and end the measurement.
