# Design spec — RemoteEar UI

The design returned against [design-brief.md](design-brief.md), extracted into an implementable
form. **This is the reference the code must match**; where the implementation deviates, the
deviation is listed at the bottom rather than left to be discovered.

- **Source artifact:** `RemoteEar-Design-standalone.html`, received 2026-09-10. A self-contained
  Claude Design canvas — 11 screens, three notification variants, both palettes, the icon.
- **Rendered copy in this repo:** [design/screens.html](design/screens.html) — the same screens with
  the bundler, fonts and React stripped out, so they open in any browser and diff as text.

Dark is the **primary** appearance. Every state is separated by **shape, text and colour at once**,
so it survives a dark room, a glance, and colour blindness.

## Colour tokens

| Token | Dark (primary) | Light | Role |
|---|---|---|---|
| `surface` | `#0E1112` | `#F7FAF9` | screen background |
| `surfaceContainer` | `#151A1B` | `#EBF0EE` | status panel |
| `surfaceContainerHigh` | `#232B2C` | `#DDE4E2` | Stop button fill |
| `onSurface` | `#E6EAE9` | `#171D1C` | primary text |
| `onSurfaceVariant` | `#9AA3A2` (7.1:1) | `#4A5453` (7.6:1) | supporting text |
| `primary` | `#6FD3B8` (10.4:1) | `#00695B` (5.7:1) | **listening** |
| `onPrimary` | `#04211B` | `#FFFFFF` | text on the Listen button |
| `paused` | `#F2B45C` (9.6:1) | `#7A4A00` (6.6:1) | **paused** |
| `error` | `#FF9A8F` (8.2:1) | `#B3261E` (6.1:1) | **stopped** |
| `errorContainer` | `#3A1512` | `#FFDAD5` | error row background |
| `neutral` | `#8C9694` | `#5C6564` | idle, unknown |

Contrast ratios are the designer's, measured against that theme's `surface`. `outline` `#48524F` /
`#B7C0BE` and the dim-state values below are also fixed by the design.

**Colour is never the only signal.** Teal and amber collapse toward each other under deuteranopia,
so each state has its own *shape*:

| State | Badge shape |
|---|---|
| Idle | hollow ring, small filled centre, `neutral` |
| Starting | dashed ring, small filled centre, `primary` |
| Listening | **two** concentric rings with a large filled centre, `primary` |
| Paused | ring containing **two vertical bars**, `paused` |
| Stopped (error) | filled circle with `!`, `error` |
| Permission missing | ring with a diagonal stroke through it, `neutral` |

## Layout

One screen, 412 × 892 dp, portrait, no app bar. Vertically, in order:

1. `REMOTEEAR` wordmark — 11 sp, letter-spacing 2.6, `#5E6867`
2. **Centred state block**, taking the remaining height: badge (72 dp; 96 dp when listening), state
   headline (30 sp idle / 34 sp listening, paused, stopped), the reason line at 19 sp where there is
   one, then supporting text at 16 sp, `max-width` 300 dp
3. **The primary control**, 92 dp tall, full width — a **46 dp-radius pill** for Listen, a **20 dp
   rounded square with a 2 dp outline** for Stop. Shape, not just label, distinguishes them
4. **Status panel** — `surfaceContainer`, 18 dp radius, rows of 10 dp dot + label + value
5. **Volume** — label plus 7 segments; no thumb, because it is not a control here
6. **Noise reduction** — the only slider on the screen
7. **Privacy footnote**, 12 sp, `#6E7877`

## Microcopy, verbatim

The wording *is* the safety feature. Screen and notification must never disagree, which is why both
read from the same string resources.

| State | Screen | Notification |
|---|---|---|
| Idle | **Not listening** · Leave the phone in the room, tap Listen, and walk away. You'll hear the room in your headphones. | none |
| Starting | **Starting** · Opening the microphone and checking your headphones. This takes a moment. | **Starting** · Getting ready to listen. |
| Monitoring | **Listening** · This room is playing to your headphones. You can put the phone down and leave. | **Listening** · The room is playing to your headphones. |
| Paused · headphones | **Paused** · Bluetooth headphones disconnected. / Reconnect your headphones and listening continues on its own. | **Paused — headphones disconnected** · You are not hearing the room. Reconnect your headphones to continue. |
| Paused · call | **Paused** · A phone call is in progress. / Listening continues by itself when the call ends. | **Paused — phone call in progress** · You are not hearing the room. Listening continues when the call ends. |
| Paused · audio | **Paused** · Another app is playing sound to your headphones. / Stop that app and listening continues by itself. | **Paused — another app is using the audio** · You are not hearing the room. Stop that app to continue. |
| Paused · microphone | **Paused** · Another app is using the microphone. / Close that app and listening continues by itself. | **Paused — another app is using the microphone** · You are not hearing the room. Close that app to continue. |
| Error | **Stopped** · The microphone could not be opened. / You are not hearing the room. Tap Listen to try again. | **Stopped — the microphone could not be opened** · You are not hearing the room. Open RemoteEar to try again. |
| Permission | **Microphone access needed** · RemoteEar plays this room to your headphones, live. It cannot do that without the microphone. Nothing is recorded, saved, or sent anywhere — the app has no internet access. | none |
| No headphones | **Not listening** · Connect the earbud you'll be carrying. RemoteEar plays only to headphones, never to the speaker. Control reads **Listen** / *Connect headphones first*. | none |

Three rules behind it:

- **Every paused and error line opens with the consequence** — *you are not hearing the room* —
  before the cause.
- **No word implies the app is watching, detecting or alerting.** It is not.
- **Nothing is described as "connected" when audio is not flowing.**

Status values: `Allowed` / `Not allowed` for the microphone, `In use by RemoteEar` while listening,
`Used by the call`, `Used by another app`; the headphone row shows the device or `Disconnected`,
`None connected`, `Busy with another app`.

## The notification

Ongoing, `IMPORTANCE_LOW`, silent, and not dismissable while listening. **The title carries the
reason**, because a collapsed notification often shows the title alone.

Error is the only variant that is dismissable and the only one whose primary action opens the app.

## Dimming while listening

After **20 s without a touch**, the listening screen drops to a black surface: state text
`#3E8C7D` (~5:1 on black), Stop `#5C6564` (~4.6:1), wordmark `#2A2F2E`, and **nothing below the Stop
button is drawn**. About a tenth of the light of the normal state, still legible at the lowest system
brightness. Any touch restores the full screen.

Status, volume and noise reduction fade out. **State text and Stop never do** — the point is that a
glance from a doorway still tells you whether audio is flowing.

## The app icon

Adaptive, 108 dp with a 72 dp safe zone, vector only. Concentric rings around a filled centre — *a
sound field*: the room, and the point you are listening from. Deliberately not an ear, a microphone
or an eye, and carrying no surveillance language.

At a 216 canvas: background `#0E1512`; outer ring ⌀150 stroke 4 `#1F4A41`; middle ring ⌀104 stroke 4
`#3E8C7D`; centre ⌀58 filled `#6FD3B8`. It reads at 24 px, where **the outer ring drops out** — the
two inner shapes are the icon, which is also the notification glyph. Monochrome variant: same shapes,
one colour.

## Deliberate deviations

| Deviation | Why |
|---|---|
| **Diagnostics section** — collapsed row at the bottom expanding to the `MIC`/`UNPROCESSED` toggle and the frame counters | Not in the design, which correctly excludes settings screens. But [S2 and S3](mvp-scope.md) exist to answer [H4](feasibility.md) and to run [Scenario G](test-matrix.md), and the A/B has to be doable on the device. Collapsed by default, so the designed screen is what a user sees |
| **"Monitoring stopped on its own" notice** ([S5](mvp-scope.md)) | The design has no slot for it. Rendered as a `paused`-coloured row in the status panel — it is a report about a past session, not a current state, and must not be confusable with one |
| Notification text says *your headphones* rather than naming the device | The device name would need `BLUETOOTH_CONNECT`, which [ADR-0007](adr/0007-minimal-permission-set.md) excludes. The status row shows the type instead |
| Volume block hidden when paused, per the design | Kept, and worth stating plainly: when audio is not flowing, a volume readout would be answering a question nobody is asking |

**Untested by design work and still open:** whether the dim state is reachable in practice. Nothing
holds the screen on — [Phase 3](implementation-plan.md) deliberately dropped `FLAG_KEEP_SCREEN_ON`
because the service is what keeps monitoring alive — so on a default phone the screen turns off
before 20 s of dimming has any value. The state is implemented for the case where the user has a
long screen timeout, not as a substitute for one.
