#!/usr/bin/env bash
# Mechanically enforces the hard invariants in CLAUDE.md that are cheap to check and expensive to
# regress. Runs in CI and locally: bash tools/check-invariants.sh
#
# This exists because both failures it guards against are SILENT. Touching the communication audio
# path still produces audio - from the wrong microphone, at narrowband quality. And an INTERNET
# permission can arrive through manifest merging without anyone writing a line of network code.
set -uo pipefail

fail=0
note() { printf '  %s\n' "$1"; }
ok()   { printf 'PASS  %s\n' "$1"; }
bad()  { printf 'FAIL  %s\n' "$1"; fail=1; }

sources=$(find app/src -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true)

# ---------------------------------------------------------------------------
# 1. ADR-0004: the communication audio path is forbidden.
#    Comment lines are stripped first, so the docs may name these APIs in order to forbid them.
# ---------------------------------------------------------------------------
# Note on the audio mode, narrowed 2026-09-10. ADR-0004 prohibits *setting* the mode - setMode is
# what hands the device to the communication path and makes the earbud's microphone the input.
# Reading it costs nothing, needs no permission, and is the only way to tell a phone call from
# another app's music without READ_PHONE_STATE, which Phase 5 needs in order to name the pause
# reason correctly. So the guard is on the setters - 'setMode(' and the Kotlin property assignment
# '.mode =' - rather than on the constant names, which the earlier version banned outright and which
# also banned comparing against them.
forbidden=(
  'startBluetoothSco'
  'stopBluetoothSco'
  'setCommunicationDevice'
  'setSpeakerphoneOn'
  'setMode[[:space:]]*\('
  '\.mode[[:space:]]*='
  'USAGE_VOICE_COMMUNICATION'
  'AudioSource\.VOICE_COMMUNICATION'
  'AudioSource\.VOICE_RECOGNITION'
  'MODIFY_AUDIO_SETTINGS'
)
if [ -z "$sources" ]; then
  ok "ADR-0004 communication-path APIs absent (no sources yet)"
else
  hits=0
  for pattern in "${forbidden[@]}"; do
    while IFS= read -r file; do
      # strip // line comments, block-comment bodies and KDoc, then search
      if sed -e 's://.*::' -e '/^[[:space:]]*[*]/d' -e '/^[[:space:]]*\/\*/d' "$file" \
           | grep -qE "$pattern"; then
        bad "ADR-0004 violated: '$pattern' used in $file"
        hits=1
      fi
    done <<< "$sources"
  done
  [ "$hits" -eq 0 ] && ok "ADR-0004 communication-path APIs absent from source"
fi

# ---------------------------------------------------------------------------
# 2. ADR-0007: no INTERNET permission, ever. Its absence is what makes
#    "audio never leaves the phone" verifiable rather than merely promised.
# ---------------------------------------------------------------------------
manifests=$(find app/src -name 'AndroidManifest.xml' 2>/dev/null || true)
if [ -z "$manifests" ]; then
  ok "ADR-0007 no INTERNET permission (no manifests yet)"
elif grep -l 'android.permission.INTERNET' $manifests >/dev/null 2>&1; then
  bad "ADR-0007 violated: INTERNET permission declared in a source manifest"
  grep -n 'android.permission.INTERNET' $manifests | while read -r l; do note "$l"; done
else
  ok "ADR-0007 no INTERNET permission in source manifests"
fi

# ---------------------------------------------------------------------------
# 3. Same check against the MERGED manifest, when a build has produced one.
#    This is the one that catches a dependency adding INTERNET behind our back.
# ---------------------------------------------------------------------------
merged=$(find app/build/intermediates -name 'AndroidManifest.xml' -path '*merged*' 2>/dev/null || true)
if [ -z "$merged" ]; then
  note "(merged manifest not built yet - run assembleDebug to include that check)"
else
  if grep -l 'android.permission.INTERNET' $merged >/dev/null 2>&1; then
    bad "ADR-0007 violated: INTERNET appears in the MERGED manifest - a dependency added it"
    grep -ln 'android.permission.INTERNET' $merged | while read -r l; do note "$l"; done
  else
    ok "ADR-0007 no INTERNET in merged manifest ($(echo "$merged" | wc -l | tr -d ' ') checked)"
  fi
  # Report the requested permission set so a reviewer sees drift.
  # Only <uses-permission> counts: an `android:permission=` attribute on a component is a
  # restriction the component imposes on its callers, not a permission this app asks for.
  # AndroidX contributes some of those (e.g. a provider guarded by DUMP) and conflating the two
  # would make this report cry wolf until someone learns to ignore it.
  printf '      requested permissions (uses-permission only):\n'
  grep -ho 'uses-permission android:name="[^"]*"' $merged \
    | sed 's/.*name="//; s/"//' | sort -u | while read -r p; do note "$p"; done
fi

# ---------------------------------------------------------------------------
# 4. Invariant 4: audio samples never reach a file, database, cache or log.
#    A coarse smell test for recording APIs that have no business being here.
# ---------------------------------------------------------------------------
if [ -n "$sources" ]; then
  if echo "$sources" | xargs grep -lE 'MediaRecorder\(|MediaMuxer|FileOutputStream|\.wav|createTempFile' 2>/dev/null | grep -q .; then
    bad "invariant 4 suspect: file/recording APIs present in audio code - audio must never be written anywhere"
    echo "$sources" | xargs grep -nE 'MediaRecorder\(|MediaMuxer|FileOutputStream|\.wav|createTempFile' 2>/dev/null | while read -r l; do note "$l"; done
  else
    ok "invariant 4 no file/recording APIs in source"
  fi
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "INVARIANT CHECK FAILED - see docs/adr/ before changing any of the above."
  exit 1
fi
echo "All invariant checks passed."
