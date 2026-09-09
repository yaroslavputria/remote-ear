# Development environment setup

> How the toolchain was installed on the primary Windows dev machine, and the traps found doing it.
>
> **Constraint: no administrator rights.** Everything below installs into the user profile. This is
> not a limitation worth working around — it turned out that only one item genuinely needs
> elevation, and it has a better alternative anyway.

## Installed (verified 2026-09-09)

| Component | Version | Location |
|---|---|---|
| JDK | Microsoft OpenJDK 21.0.12.1 LTS | `%LOCALAPPDATA%\Programs\jdk-21.0.12.1+1` |
| Android cmdline-tools | 23.0.0 | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| platform-tools (`adb` 1.0.41) | 37.0.1 | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| SDK Platform 36 | Android 16 | `%LOCALAPPDATA%\Android\Sdk\platforms\android-36` |
| Build-Tools | 36.0.0 | `%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0` |
| Google USB driver *(files only)* | — | `%LOCALAPPDATA%\Android\Sdk\extras\google\usb_driver` |
| Android Studio | 2026.1.4.7 ("Quail") | `%LOCALAPPDATA%\Programs\android-studio` |

User-scope environment variables (no elevation needed — only machine scope requires it):

```text
JAVA_HOME    = %LOCALAPPDATA%\Programs\jdk-21.0.12.1+1
ANDROID_HOME = %LOCALAPPDATA%\Android\Sdk
PATH        += %JAVA_HOME%\bin ; %ANDROID_HOME%\platform-tools ; %ANDROID_HOME%\cmdline-tools\latest\bin
```

Stored as absolute paths, not `%VAR%` references: `SetEnvironmentVariable` writes `REG_SZ`, which
does not expand.

### There are two JDKs on this machine — know which one you get

A pre-existing **Temurin JDK 17.0.20.1 at `C:\jdk17`** was already on the user `PATH`, ahead of the
new entry. So:

| Resolution route | Gets |
|---|---|
| `java` typed in a new shell | **JDK 17** (`C:\jdk17\bin` comes first on `PATH`) |
| `JAVA_HOME` | **JDK 21** (`%LOCALAPPDATA%\Programs\jdk-21.0.12.1+1`) |
| Gradle | **JDK 21** — it honours `JAVA_HOME` before `PATH` |
| Android Studio | its own bundled JetBrains Runtime, independent of both |

This is deliberately left alone rather than "fixed": `C:\jdk17` may belong to other work, and
reordering a shared `PATH` to suit one project is the sort of side effect that surprises people
later. Either JDK satisfies AGP 8.x (17 is the floor), so nothing here is broken — but if you ever
see a Java-version discrepancy between a terminal and a Gradle build, **this is why**. Pin it
explicitly with `org.gradle.java.home` in `gradle.properties` if it ever matters.

## Deliberately not installed

**No emulator, no system images.** They are the largest part of a normal Android setup (~1.5–2 GB)
and this project cannot use them: **the emulator has no Bluetooth audio**, so it can verify none of
the open hypotheses in [feasibility.md](feasibility.md). Android Studio's setup wizard offers them
by default — decline. See [test-matrix.md](test-matrix.md).

**No NDK.** Oboe/AAudio is deferred ([ADR-0003](adr/0003-audiorecord-audiotrack-for-mvp.md)).

**No standalone Gradle.** The Gradle wrapper handles it once the project exists.

**Node is present but irrelevant** — [ADR-0001](adr/0001-native-kotlin-over-react-native.md) dropped
React Native.

## Traps found during setup

Recorded because each cost real time and all four will recur.

### 1. `MAX_PATH` breaks archive extraction

The cmdline-tools zip contains this genuine file:

```text
lib\external\com\google\guava\listenablefuture\9999.0-empty-to-avoid-conflict-with-guava\
  listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar
```

That is ~151 characters of relative path. Extracting under a deep scratch directory exceeded the
260-character limit and produced a **silently partial** extraction — `sdkmanager.bat` was present
while `lib/` was incomplete, which would have failed later and looked like a different bug.

**Rule: extract via a short path, then move; and verify by file count, not by absence of an error.**

```powershell
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
$expected = ($zip.Entries | Where-Object { $_.Name -ne '' }).Count
$zip.Dispose()
# ...extract...
$actual = (Get-ChildItem $dest -Recurse -File).Count   # must equal $expected
```

Enabling Windows long-path support would fix this properly, but it is an `HKLM` registry change and
therefore needs admin.

### 2. `Expand-Archive -Force` is unreliable on PowerShell 5.1

It tries to delete pre-existing entries that do not exist, throws a stream of `PathNotFound` errors,
and aborts leaving **nothing** extracted — while looking like noise rather than failure. Use
`[System.IO.Compression.ZipFile]::ExtractToDirectory` instead.

### 3. `sdkmanager` is deprecated, and its license prompt cannot be piped

`sdkmanager` now prints:

> The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead. […] `android sdk` is
> the replacement for `sdkmanager`.

Piping `y` into `sdkmanager --licenses` from PowerShell **does not work** — the prompt is not
satisfied and every package is silently skipped with "license is not accepted", while the command
still exits `0`. An exit code of zero here means nothing.

Use `android sdk install` instead: it accepts the licenses without prompting.

### 4. The `android` CLI works but crashes on exit

`android.exe` (note: `.exe`, not `.bat`, unlike its neighbours in `cmdline-tools\latest\bin`) is the
sanctioned replacement, and `android sdk install <pkg>` downloads, unzips, and installs correctly.
But in this build (CLI `1.0.16261425`):

- `android sdk list` prints installed packages, then **crashes** with `0xC0000409`
  (`STATUS_STACK_BUFFER_OVERRUN`) while fetching the available-package list.
- `android sdk install` **completes the install successfully and then crashes on exit**, returning
  `-1073740791`.

**So verify installs against the filesystem, not the exit code.** Both tools mislead in opposite
directions: `sdkmanager` returns success having done nothing, and `android` returns failure having
done the job.

## The one thing that needs admin

**Installing the Google USB driver.** The SDK package only places `.inf` files on disk (done above);
binding them to a device in Device Manager requires elevation.

This is very likely not worth requesting:

1. Windows 11 enumerates most phones for adb with its inbuilt driver — Pixel and most Samsung
   devices work unmodified. Plug in and check.
2. **Wireless debugging (Android 11+) needs no USB driver at all**, and this project wants it
   regardless: Scenarios B, C and G all place the phone in another room, where a cable does not
   reach.

Request admin only if USB fails *and* wireless debugging is unavailable — a much narrower ask,
backed by evidence.

## Phone-side steps (manual)

Not installs, but required before Phase 2:

1. **Settings → About phone → tap Build number 7×** to unlock Developer options.
2. **Developer options → USB debugging** on.
3. **Developer options → Wireless debugging** on, then pair:

```bash
adb pair <phone-ip>:<pairing-port>     # code shown on the phone
adb connect <phone-ip>:<port>
adb devices -l                          # confirm it appears
```

## Verifying the toolchain

```bash
java -version                                    # 21.x
adb version                                      # 1.0.41
android sdk list                                 # installed packages (ignore the crash after)
ls "$ANDROID_HOME/platforms"                     # android-36
ls "$ANDROID_HOME/build-tools"                   # 36.0.0
```

A new shell is needed for the environment variables to be visible.

## Second machine?

Repeat with the same constraints. The two decisions worth keeping: extract from **short paths with
count verification**, and use **`android sdk install`** rather than `sdkmanager`.
