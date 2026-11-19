#!/usr/bin/env bash
# Build, publish and describe a release in one step.
#
# version.json used to be written by hand next to a separately built APK, which
# is how a 0.5.0/10 APK ended up advertised as 0.4.3/11. Now the metadata is read
# back out of the built APK, so the two cannot disagree.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:?usage: scripts/publish.sh \"release notes\" [apk-dir]}"
APK_DIR="${2:-$HOME/drinkwatch/apk}"
AAPT="${AAPT:-$HOME/android-build/sdk/build-tools/34.0.0/aapt2}"
APK="app/build/outputs/apk/release/app-release.apk"

./scripts/build.sh assembleRelease
[ -f "$APK" ] || { echo "no APK produced" >&2; exit 1; }

VERSION_CODE="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
VERSION_NAME="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"

mkdir -p "$APK_DIR"
cp "$APK" "$APK_DIR/drinkwatch.apk"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$NOTES" "$APK_DIR/version.json" <<'PY'
import json, sys
code, name, notes, target = sys.argv[1:5]
with open(target, "w") as handle:
    json.dump({"versionCode": int(code), "versionName": name, "notes": notes}, handle)
print(f"published {name} (versionCode {code})")
PY
cat "$APK_DIR/version.json"
