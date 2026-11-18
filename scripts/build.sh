#!/usr/bin/env bash
# Build the DrinkWatch APK. On powermax the toolchain lives in ~/android-build.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/android-build/jdk/jdk-17.0.20.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-build/sdk}"
GRADLE_BIN="${GRADLE:-$HOME/android-build/gradle/gradle-8.9/bin/gradle}"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
if [ "$#" -eq 0 ]; then
  set -- assembleDebug
fi
exec "$GRADLE_BIN" --no-daemon "$@"
