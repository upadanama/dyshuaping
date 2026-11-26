#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  APK="dist/app-debug.apk"
fi
if [ ! -f "$APK" ]; then
  echo "APK not found. Run ./build.sh first." >&2
  exit 1
fi

ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ] && [ -x "$HOME/android-sdk/platform-tools/adb" ]; then
  ADB="$HOME/android-sdk/platform-tools/adb"
fi
if [ -z "$ADB" ]; then
  echo "adb not found. Install the APK manually: $(pwd)/$APK" >&2
  exit 1
fi

"$ADB" install -r "$APK"
