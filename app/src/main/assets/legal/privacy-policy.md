# Privacy policy

**RemoteEar collects nothing.** No accounts, no analytics, no crash reporting, no advertising, no
identifiers, and no network access of any kind.

Last updated: 10 September 2026.

## What the app does

RemoteEar captures this phone's built-in microphone and plays it, live, to Bluetooth headphones you
carry into another room. Everything happens on the phone.

## Audio

- Audio is held only in short memory buffers — a fraction of a second in total — while it is on its
  way to your headphones.
- It is never written to a file, a database, a cache, or a log.
- It is never sent anywhere. It cannot be: the app has no internet permission.
- Nothing is kept. When you stop listening, the buffers are released.

## No internet access

The app does not request the `INTERNET` permission. Android does not allow an app without it to open
a network connection at all — so this is enforced by the operating system rather than by our good
intentions, and you can check it yourself with the phone connected to a computer:

`adb shell dumpsys package com.yputria.remoteear`

The permissions listed there are the only ones the app has.

## Permissions, and why each one exists

- **Microphone** — to capture the room you want to hear. This is the app's entire function.
- **Foreground service, microphone type** — so listening keeps working with the screen off while the
  phone is in another room. Android requires this to be declared, and shows an ongoing notification
  the whole time it runs.
- **Notifications** — for that notification, which is how you can tell whether the app is listening,
  and stop it without walking back to the phone.

There is no permission for storage, contacts, location, the camera, phone state, or the network.

## Children

RemoteEar is often used to listen to a room where a child is sleeping. No data about anyone — child
or adult — is collected, stored, or shared, because none is collected at all.

## Third parties

There are none. The app has no third-party libraries beyond Google's own AndroidX and Jetpack
Compose, no SDKs, no trackers, and no service providers, because it never communicates with
anything.

## Changes

If this policy ever changes, the change ships with a new version of the app and appears on this
screen. There is no copy on a server that can change without you.

## Contact

yaroslav.putria@gmail.com

## Source

RemoteEar is open source under the MIT licence. Every claim above can be checked against the code.
