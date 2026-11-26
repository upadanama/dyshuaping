#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Works both with the local toolchain used to create this project and with a
# normal Android Studio / command-line environment.
if [ -z "${JAVA_HOME:-}" ] && [ -x "$HOME/toolchains/dyshuaping/jdk/bin/java" ]; then
  export JAVA_HOME="$HOME/toolchains/dyshuaping/jdk"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/android-sdk" ]; then
  export ANDROID_HOME="$HOME/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

./gradlew --no-daemon assembleDebug

mkdir -p dist
cp -f app/build/outputs/apk/debug/app-debug.apk dist/app-debug.apk
echo
echo "APK: $(pwd)/dist/app-debug.apk"
ls -lh dist/app-debug.apk
sha256sum dist/app-debug.apk
