# Test run — 2026-09-10 — OnePlus CPH2399 — Phase 3 foreground service

**Phase 3 gate: Scenarios C and D.** Does monitoring survive the app being backgrounded and the
screen locking?

## Setup

| | |
|---|---|
| Device | OnePlus CPH2399 (`OP557AL1`), ColorOS/OPLUS, Android 14 / API 34 |
| Headphones | A2DP; model and codec still **not recorded** *(gap carried over from the Phase 2 run)* |
| App build | `5055d49` |
| Input source | `MIC` (`UNPROCESSED` unsupported on this device) |
| Controls as tested | output volume 62%, **noise reduction 93%**, **device noise suppression ON** |

## Result

| # | Scenario | Result |
|---|---|---|
| C | Screen locked | **pass** — monitoring continued |
| D | App backgrounded | **pass** — monitoring continued |

Tester's report: *"works good, lock screen as well."*

## Objective evidence

Not just the report — the platform's own view of the service:

```text
ServiceRecord{... com.yputria.remoteear/.monitor.MonitoringService}
  isForeground=true foregroundId=1 types=00000080
  foregroundNoti=Notification(channel=monitoring ... flags=0x42 category=service actions=1 vis=PRIVATE)
  createTime=-2m25s306ms
  createdFromFg=true
  startRequested=true delayedStop=false stopIfKilled=true callStart=true
```

Reading that line by line:

| Field | Meaning |
|---|---|
| `isForeground=true` | Genuinely promoted, not a plain background service |
| `types=00000080` | `0x80` = 128 = **`FOREGROUND_SERVICE_TYPE_MICROPHONE`** — the right type is active |
| `channel=monitoring`, `flags=0x42` | Our low-importance channel; `0x40 \| 0x02` = foreground-service + ongoing |
| `actions=1` | The **Stop** action is present, so a session can be ended without reopening the app |
| `createdFromFg=true` | Started from a visible Activity — satisfies the while-in-use restriction ([android-constraints.md](../android-constraints.md)) |

And from the app's own UI while monitoring:

```text
Monitoring — "This room is playing to your headphones."
Routing: in=BUILTIN_MIC out=BLUETOOTH_A2DP
```

So the routing assertion still holds through a service-hosted session, not just the Phase 2
in-Activity one.

## What is **not** established

**The 30-minute duration criterion was not met.** At the time of measurement the service had been up
**2 m 25 s**. The *mechanism* is verified — foreground promotion, the microphone type, the
notification, survival across backgrounding and lock — but endurance is not.

That is deliberately not treated as a blocker. The multi-hour question is
[Scenario G](../test-matrix.md) in Phase 6, which exists precisely to measure it, and the risk it
addresses ([R1](../risks.md), OEM process kill) cannot be settled by a 30-minute run either. Phase 4
proceeds; the duration evidence comes from Scenario G.

Also still not run: E (Bluetooth disconnect) and F (calls) — both Phase 5, since the pause triggers
are not wired yet. A call during this build will do something untested.

## Observation worth following up

The tester ran **noise reduction at 93%** *and* **device noise suppression ON**, and was happy with
the result.

Two notes rather than a problem:

- At 93% the high-pass cutoff is ≈373 Hz, which is aggressive — it will sound noticeably thin, and
  it removes most of the low band. That is a legitimate preference, not a fault.
- But this is the combination most likely to hide the quiet sounds the product exists to relay
  ([risk R2](../risks.md)). It has only been judged against ordinary room noise so far.
  **Worth one deliberate check in a genuinely quiet room**: can you still hear breathing and
  rustling with both engaged? If not, the honest fix is a gentler default rather than a hidden
  caveat.

## Follow-ups

- [ ] Quiet-room check with noise reduction high **and** device suppression on (R2)
- [ ] Latency re-test with the corrected output buffer — still outstanding from Phase 2
- [ ] Capture the Bluetooth codec and earbud model
- [ ] Confirm the notification is actually visible (`POST_NOTIFICATIONS` read `granted=false` earlier)
- [ ] Duration: folded into Scenario G, Phase 6
