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

## Run 1 — first nine minutes

```text
t=1.0min  in=2884800   out=2884800   underruns=0  read=18982/78572us  write=1011/79045us  drop:0,pad:0  peakBacklog=40ms
t=2.0min  in=5781120   out=5781120   underruns=0  read=18616/78572us  write=1368/79045us  drop:0,pad:0  peakBacklog=40ms
t=3.0min  in=8676480   out=8676480   underruns=0  read=18131/78572us  write=1848/80165us  drop:0,pad:0  peakBacklog=60ms
t=4.0min  in=11571840  out=11571840  underruns=0  read=17454/81847us  write=2524/86217us  drop:0,pad:0  peakBacklog=80ms
t=5.0min  in=14467200  out=14467200  underruns=0  read=16692/81847us  write=3286/86217us  drop:0,pad:0  peakBacklog=80ms
t=6.0min  in=17360640  out=17360640  underruns=0  read=16729/85321us  write=3250/86217us  drop:0,pad:0  peakBacklog=80ms
t=7.0min  in=20252160  out=20252160  underruns=0  read=17022/85321us  write=2956/87004us  drop:0,pad:0  peakBacklog=80ms
t=8.0min  in=23143680  out=23143680  underruns=0  read=17214/85321us  write=2764/87004us  drop:0,pad:0  peakBacklog=80ms
t=9.0min  in=26036160  out=26036160  underruns=0  read=17333/85321us  write=2643/88887us  drop:0,pad:0  peakBacklog=80ms
```

**The backlog rose 40 → 80 ms over four minutes and then went flat for five.** Because it is a
high-water mark, a flat peak means the current value has not exceeded 80 ms since — so this was the
pipeline *settling*, not drift accumulating. Real drift would keep pushing the peak up.

So after nine minutes and 26 million frames: **no underrun, no correction needed, and no frame
either lost or invented.** The drift correction has not had to do anything, which is the best
possible outcome for it — and it has not misfired either, which was the risk in adding it.

The read and write means also settled, and they settled the *opposite way round* to expectation.

| Reading | Value | Reading it |
|---|---|---|
| Underruns | **0** | The output has not starved once |
| Drift corrections | **0 drops, 0 pads** | Nothing needed correcting, and — just as usefully — **no false positives**: the drop threshold does not trip on ordinary jitter |
| Peak backlog | **80 ms, flat for five minutes** | Settled, not growing. Under the 100 ms drop threshold with room to spare |
| `framesIn == framesOut` | exactly, at 26 million | No frames lost, none invented |
| Mean read block | ~17 ms, falling slightly | **The read paces the loop** — see below |
| Mean write block | 1.0 → 3.3 → 2.6 ms | The 215 ms A2DP track buffer never comes close to full |
| **Max read / max write** | **85.3 ms / 88.9 ms**, creeping up | **The one thing to watch.** See below |
| CPU | 16.6 % of one core | ~2 % of an eight-core phone |
| Memory | 81 MB PSS / 187 MB RSS | Ordinary for a Compose app; flat so far |
| Thermal status | `mStatus=0` on every sensor | No throttling. Battery 31.2 → 33.5 °C, but it is **charging at 100 %**, so that rise is not ours to claim |

### The stalls — the open risk

The maxima are not a single start-up event. They creep: read 78.6 → 81.8 → 85.3 ms, write 79.0 →
86.2 → 87.0 → 88.9 ms, a new worst case every couple of minutes.

That matters because **the record buffer is 80 ms** ([ADR-0009](../adr/0009-buffer-sizing-measured.md)),
and a stall longer than the buffer is exactly how a captured frame is lost. And frame loss of that
kind **would not show in these counters**: `framesIn` counts what we read, so if the platform drops
a buffer before we get to it, the number stays consistent and the evidence is an audible click
instead.

So the honest position after nine minutes: the numbers are clean, and the numbers cannot rule this
one out. **This is the specific thing a human ear at hour four is for.** It is also the argument for
tracking maxima at all, now concrete: a mean over 26 million frames shows 17 ms and hides an 89 ms
stall completely.

If clicks do turn up, the fix is not mysterious — the record buffer was set to
`max(minRecord * 2, 4 frames)` ≈ 80 ms in ADR-0009, and raising it costs input latency in a budget
that A2DP already dominates with 215 ms on the output side.

### The read paces the loop, not the write

Reads block ~17 ms; writes return in 1–3 ms. [ADR-0009](../adr/0009-buffer-sizing-measured.md) sized
the buffers on the assumption that the A2DP output would be the constraint — it is the larger buffer
by far — but in practice the output drains comfortably and the loop is paced by waiting for the
microphone. Not a defect, and it does change where to look first when latency needs reducing.

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
