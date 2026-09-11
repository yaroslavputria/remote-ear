# Release guide — getting RemoteEar into Google Play

Everything that can be prepared in advance is in this repository. This document is the part only you
can do, in order, with the exact text to paste where a form asks for it.

> **Two things to know before starting.** A Play developer account costs **$25, once**. And a
> continuous-microphone app framed as a baby monitor attracts review attention
> ([risk R3](risks.md)) — which is why the wording below is careful never to promise detection or
> alerting, and why the absence of `INTERNET` is worth stating plainly.

## What is already done

| | Where |
|---|---|
| Minified, shrunk release build (R8) | `app/build.gradle.kts` |
| Signing wired to a gitignored `keystore.properties` or env vars | same |
| Store icon, 512 × 512 | [store/icon-512.png](../store/icon-512.png) |
| Feature graphic, 1024 × 500 | [store/feature-1024x500.png](../store/feature-1024x500.png) |
| Three phone screenshots | [store/screenshots/](../store/screenshots/) |
| Privacy policy and terms | `app/src/main/assets/legal/` — shipped in the app **and** the file you will link to |
| Data Safety answers | below, and [privacy.md](privacy.md) |
| Listing copy | below |
| Foreground-service justification | below |

Regenerate the graphics any time with `node tools/make-store-assets.js`; they are drawn from the same
geometry as the launcher icon so the two cannot drift.

---

## Step 1 — Create the signing key (once, and never lose it)

`keytool` ships with the JDK and is **not on this machine's `PATH`** — the same reason `./gradlew`
needs `JAVA_HOME` set by hand here ([dev-setup.md](dev-setup.md)). Call it by its full path, from
Git Bash, in the repo root:

```bash
/c/jdk17/bin/keytool -genkeypair -v \
  -keystore remote-ear-release.jks \
  -alias remote-ear \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Yaroslav Putria, O=RemoteEar, C=UA"
```

It will ask for a keystore password and then whether to reuse it for the key — same password for
both is fine.

**Keep the `.jks` file and its passwords somewhere you will still have them in five years** — a
password manager, not just this laptop. Even with Play App Signing (step 7) enabled, losing the
upload key means a support round-trip to replace it.

Then create `keystore.properties` at the repo root — **gitignored, never committed**:

```properties
storeFile=C:/path/to/remote-ear-release.jks
storePassword=…
keyAlias=remote-ear
keyPassword=…
```

Verify the build picks it up:

```bash
./gradlew :app:bundleRelease
```

The output is `app/build/outputs/bundle/release/app-release.aab`. **Play wants the `.aab`, not an
APK.** Without `keystore.properties` this same command still succeeds and produces an *unsigned*
bundle — deliberate, so CI can build and check the release variant without a key. An unsigned bundle
looks identical in the file listing and **Play rejects it on upload**, so check rather than assume:

```bash
/c/jdk17/bin/jarsigner -verify app/build/outputs/bundle/release/app-release.aab   # "jar verified."
/c/jdk17/bin/keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

### The upload key, for the record

*Created 2026-09-11. Not a secret — a certificate fingerprint is public by design, and having it
written down is how you confirm later that a build was signed with the key you think it was.*

```
Owner/Issuer:  CN=Yaroslav Putria, O=RemoteEar, C=UA
Algorithm:     SHA384withRSA, 4096-bit
Valid:         2026-09-11 → 2054-01-27
SHA-256:       90:57:79:E3:C4:E7:4B:B5:2A:B9:AE:D7:BD:A3:BD:07:
               4C:D1:67:02:3C:60:05:4E:54:34:F5:34:7D:6F:95:51
```

`jarsigner -verify` also prints warnings about entries "signed in JarFile but not in
JarInputStream", and about a self-signed chain with no timestamp. **Both are expected and neither
matters here**: an upload key is meant to be self-signed, and the streaming-order complaint is an
artefact of how a bundle is laid out, not a defect in the signature. `jar verified.` is the line that
counts.

## Step 2 — Publish the privacy policy at a URL

Play requires the policy at a **public web address**, not only inside the app. **Done** — the
repository is public and pushed, so paste this into the Console:

```
https://github.com/yaroslavputria/remote-ear/blob/main/app/src/main/assets/legal/privacy-policy.md
```

*Verified live 2026-09-10.* It is the same file the app renders on its Privacy policy screen, which
is the point: a reader can compare the two and find them identical, rather than taking the app's word
for it.

Both documents are final: the contact address is the author's, and the terms are governed by the law
of Ukraine.

## Step 3 — Account type: personal, not organization

The Console asks this once and it is awkward to change, so it is worth thirty seconds of thought.

| | Personal | Organization |
|---|---|---|
| Verification | Your ID and address | The **legal entity**, which needs a **D-U-N-S number** — free from Dun & Bradstreet, but issuing can take days to weeks |
| Suits | An individual publishing their own work | A registered company publishing as itself |
| Catch | **12 testers, opted in continuously for 14 days**, before you can apply for production access — *[verified 2026-09-10](https://support.google.com/googleplay/android-developer/answer/14151465)*, applies to personal accounts created after 13 Nov 2023 | Not stated on that page; do not assume it is exempt |

**Choose personal.** RemoteEar is an individual's MIT-licensed side project, the `LICENSE` and every
commit are in your own name, and there is no company to attribute it to. Registering as an
organization would mean obtaining a D-U-N-S number and verifying an entity in order to publish a free
app that one person wrote.

The 12-tester rule is the real cost of that choice, and it is not a bad thing here: this app has been
tested on **one phone with one pair of earbuds**, and its top-ranked risk is that some manufacturers
kill it overnight ([R1](risks.md)). Twelve people on twelve different phones for two weeks is exactly
the evidence this project is missing. Friends and family opting in via the closed-testing link
counts.

> If you *do* have a registered business and would rather ship under it, that is a legitimate reason
> to pick organization — just start the D-U-N-S request first, because it is the long pole.

## Step 4 — Create the app in Play Console

At <https://play.google.com/console> → **Create app**.

| Field | Answer |
|---|---|
| App name | `RemoteEar` |
| Default language | English (United States) or (United Kingdom) — the copy below is British English |
| App or game | App |
| Free or paid | **Free** |
| Declarations | Confirm it meets the Developer Program Policies and US export laws |

## Step 5 — Store listing

**App name** (30 characters max):

```
RemoteEar
```

**Short description** (80 characters max — this one is 79):

```
Hear one room through your headphones. No recording, no internet, no accounts.
```

**Full description** (4000 characters max):

```
RemoteEar turns your phone into a microphone you can listen to from another room.

Leave the phone where you want to hear — a child's bedroom, a workshop, a room where
someone is resting — put a Bluetooth earbud in your ear, and tap Listen. You hear that
room, live, wherever you are in the house.

That is all it does.

NOTHING LEAVES YOUR PHONE

RemoteEar does not request internet permission. Not "does not upload" — cannot. Android
will not let an app without that permission open a network connection at all, and you can
check it yourself in the app's permission list. There are no accounts, no analytics, no
crash reporting, no advertising and no third-party libraries beyond Google's own.

Audio is never written to a file. It exists for a fraction of a second in memory on its way
to your headphones, and then it is gone.

IT TELLS YOU WHEN IT STOPS

A monitor that goes quiet without saying so is worse than no monitor. So when something
interrupts it, RemoteEar says which thing, on screen and in the notification:

• your headphones disconnect — and it resumes on its own when they come back
• a phone call starts — and it resumes when the call ends
• another app takes the audio or the microphone

Every one of those messages starts with what matters: you are not hearing the room.

BUILT FOR A DARK ROOM

Dark by default, with one large button, and text large enough to read at a glance at 3am.
The screen dims itself while listening so it emits almost no light, and any touch brings it
back. Volume is your phone's own volume — no second control to forget about. One
optional noise-reduction slider, off by default, with an honest warning that turning it up
can hide quiet sounds.

WHAT IT IS NOT

RemoteEar is not a baby monitor, a medical device, a security system or a safety device,
and it must not be relied on as one. It does not detect crying, it does not alert you, and
it cannot promise to keep running: Bluetooth range, batteries, phone calls and your phone
manufacturer's battery management can all stop it. Never use it as the only way you are
keeping track of a child or anyone who depends on being heard. Stay in reach and check in
person.

It needs a Bluetooth headphone or earbud. It plays only to headphones, never to the
phone's speaker.

Open source under the MIT licence.
```

**App icon**: [store/icon-512.png](../store/icon-512.png)
**Feature graphic**: [store/feature-1024x500.png](../store/feature-1024x500.png)
**Phone screenshots**: the three in [store/screenshots/](../store/screenshots/) — listening, paused
with a stated reason, and the control disabled with an explanation. That order tells the story the
listing claims.

> **Two caveats on the screenshots.** They are real captures from a real phone, which means the
> status bar carries **your** notification icons — Instagram, Gmail and so on. Harmless, but it does
> tell the world which apps you have. And there is no screenshot of the ready-to-listen state with
> headphones connected, because the earbuds were disconnected when they were taken.
>
> To retake them cleanly, put the phone in demo mode first so the status bar is generic:
>
> ```bash
> adb shell settings put global sysui_demo_allowed 1
> adb shell am broadcast -a com.android.systemui.demo -e command enter
> adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
> adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
> adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
> # take the screenshots, then:
> adb shell am broadcast -a com.android.systemui.demo -e command exit
> ```
>
> Play validates image dimensions on upload and tells you immediately if it objects, so treat the
> Console as the authority on sizes rather than any number written here.

## Step 6 — App content declarations

This is the section that actually gates release. Console → **Policy → App content**.

### Privacy policy
The URL from step 2.

### Data safety
The honest answers, which are unusually simple here — see [privacy.md](privacy.md):

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Does your app collect or share audio? | **No.** Audio is processed ephemerally in memory and never transmitted or stored. Play's definition of "collection" is transmission off the device; nothing leaves |
| Is all user data encrypted in transit? | Not applicable — no data is transmitted |
| Do you provide a way for users to request data deletion? | Not applicable — nothing is retained |

If a question insists on a data type, the truthful answer everywhere is "not collected". The absence
of `INTERNET` in the manifest is your evidence if anyone asks.

### Foreground service permissions

**This one blocks review if skipped**, and it is easy to miss. Play asks every app that declares a
foreground-service type to justify it, and may ask for a short screen recording.

- Permission: `FOREGROUND_SERVICE_MICROPHONE`
- Purpose to select: **the app records audio while in the background at the user's request** (the
  option wording varies; pick the one about continuing to use the microphone with the app not in the
  foreground).

Justification text:

```
RemoteEar plays the phone's built-in microphone live to the user's Bluetooth headphones so
they can hear one room while they are in another. The whole purpose of the app requires the
microphone to keep working after the user leaves the phone behind and the screen turns off,
which is only possible with a microphone-type foreground service.

The service is started only by an explicit tap on the Listen button in a visible screen, and
it shows an ongoing notification for its entire lifetime, stating whether audio is currently
flowing and offering a Stop action. Audio is never recorded, stored or transmitted: the app
does not request the INTERNET permission, so it cannot send anything anywhere.
```

If a demo video is requested, screen-record 30 seconds: open the app, tap Listen, show the
notification saying "Listening", lock the screen, unlock, tap Stop.

### Content rating
Fill in the questionnaire honestly. Everything is "no": no violence, no sexual content, no profanity,
no gambling, no user-generated content, no sharing of location or personal information. It should
come back as suitable for everyone.

### Target audience
**Not designed for children.** The *user* of this app is an adult — a parent, or someone looking
after another adult. Declaring a child audience would pull the app into Play's Families programme
with a much larger compliance surface, for no benefit: children are not the users, even though a
child may be in the room.

### Other declarations
- Ads: **no**.
- In-app purchases: **no**.
- Government app: no. News app: no. COVID-19 app: no.
- Health: **no** — and be careful here. RemoteEar makes no health or safety claim, and the terms say
  so explicitly. Do not describe it as a medical or safety device anywhere in the listing.

## Step 7 — The four stages on the Console dashboard

The dashboard shows Internal testing, Finish setting up your app, Closed testing and Production as a
list. They are **not** four things to do in that order — there is a dependency, and getting it wrong
costs a fortnight:

```
Internal testing  ──────────────────────────────►  available now, needs nothing
                                                   (this is where the release build gets its
                                                    first run on a device)

Finish setting up  ──►  Closed testing  ──►  14 days  ──►  Apply for production
   (declarations         (needs setup      (12 testers
    + listing)            finished)         opted in, continuously)
```

**The 14-day clock is the only thing here that cannot be hurried**, and it cannot start until the
setup is finished. So the order that wastes no time is: internal testing *now* (it blocks nothing),
setup *immediately* after, closed test the moment setup allows, and then two weeks of waiting during
which you can do anything you like — including building the next app.

### 7a. Internal testing — do this first, today

No setup required, builds available within minutes, up to 100 testers.

1. **Play App Signing**: accept when prompted (the default). Google holds the app signing key and
   your `.jks` becomes the *upload* key — which is what lets a lost key be replaced without losing
   the app.
2. Release → Testing → **Internal testing** → Create new release → upload `app-release.aab`.
3. Release notes:
   ```
   First internal build.
   ```
4. Testers tab → add your own Google account → copy the opt-in link → open it on the phone → install.
5. **Then actually use it for ten minutes.** This is the first time the release build has ever run:
   R8 has rewritten the bytecode and resource shrinking has removed anything it thought unused.
   Check listening starts, the notification appears, the interruption messages still read correctly,
   and the Privacy policy and Terms screens still open — those read from `assets/`, which is exactly
   the kind of thing a shrinker can get wrong.

### 7b. Finish setting up your app

The **App content** declarations and the store listing — steps 5 and 6 above. Every answer is
written out there. This is form-filling, perhaps an hour, and it is what unlocks closed testing.

### 7c. Closed testing — start the clock

1. Release → Testing → **Closed testing** → create a track (the default "Alpha" is fine).
2. Upload the same `.aab`.
3. Testers: create a **Google Group** and use its address, rather than pasting twelve individual
   emails. You will publish a second app from this account, and a group can be reused; a pasted list
   cannot.
4. Send the opt-in link to twelve people. **They must each open it, accept, and install** — Play
   counts *opted-in testers*, not invitations sent. The Console shows the live count.
5. Leave it alone for fourteen days. **Do not remove testers**; the requirement is twelve opted in
   *continuously* for the preceding fourteen days.

> Twelve people is the real work in this whole document. Start collecting them before you need them.
> Ask for phones that are *not* OnePlus: the one thing this project most needs to learn is whether
> other manufacturers' battery managers kill the service overnight ([R1](risks.md)), and twelve
> identical phones would teach it nothing.

### 7d. Production

After fourteen days with twelve testers still opted in, the Dashboard offers **Apply for production
access**. The application asks about the closed test — what you learned, what you changed. Answer it
from the test-run records in `docs/test-runs/` and from whatever the twelve testers reported; that is
what those files are for.

Then Production → Create new release → same bundle → roll out.

## Step 8 — For every later version

Bump both values in `app/build.gradle.kts`:

```kotlin
versionCode = 2          // must increase for every upload; Play rejects a repeat
versionName = "0.1.1"    // what the user sees, and what About shows
```

Then `./gradlew :app:bundleRelease` and upload. `versionCode` is the one Play enforces.

---

## Before you press submit

Honest state of the thing you are about to publish, so the decision is an informed one:

- **The release build has never been run on a device.** It builds, it is minified by R8, and its
  merged manifest has been checked — but every hardware test in `docs/test-runs/` used a debug
  build. Internal testing in step 6 exists for exactly this.
- **[R1](risks.md) is unresolved**: nobody knows whether ColorOS — or Xiaomi, or Samsung — lets this
  app run all night. The app now tells the user honestly when a session ended on its own, and that
  mitigation itself has not been verified on a device.
- **One phone, one pair of earbuds.** No LE Audio hardware, no near-AOSP baseline, and nothing tested
  on Android 15 or 16.
- **No numeric latency figure**, and no measured battery drain.

None of that is a reason not to publish something free and open-source. It is a reason to go through
internal testing rather than straight to production, and to keep the listing's promises as narrow as
they are written above.
