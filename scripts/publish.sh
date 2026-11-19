#!/usr/bin/env bash
# Build, verify, publish and describe a release in one step.
#
# version.json used to be written by hand next to a separately built APK, which is how
# a 0.5.0/10 APK ended up advertised as 0.4.3/11. It is now read back out of the built
# APK, but that only makes the pair agree: it cannot tell whether the APK is fit to
# publish. A release built on a machine without keystore.properties was debug-signed
# and copied anyway, and every installed app must refuse a foreign key
# (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — the uninstall that follows wipes the user's
# token, watchlist, basket and alerts because allowBackup is off.
#
# So nothing is written until the APK's signer matches the certificate in the keystore
# that keystore.properties names, and its versionCode is newer than the one the server
# already advertises. The APK is renamed into place first and version.json last, so a
# reader sees either the old pair or the new pair — the metadata rename is the commit
# point, and an interrupted run can simply be repeated.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="${1:?usage: scripts/publish.sh \"release notes\" [apk-dir]}"
APK_DIR="${2:-$HOME/drinkwatch/apk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/android-build/jdk/jdk-17.0.20.1+1}"
BUILD_TOOLS="${BUILD_TOOLS:-$HOME/android-build/sdk/build-tools/34.0.0}"
AAPT="${AAPT:-$BUILD_TOOLS/aapt2}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS/apksigner}"
KEYTOOL="${KEYTOOL:-$JAVA_HOME/bin/keytool}"
APK="app/build/outputs/apk/release/app-release.apk"
# The server serves one fixed /app/drinkwatch.apk (app/api/appdownload.py) and links to
# it from the install page, so the published file keeps that name; version.json is what
# identifies the build.
APK_NAME="drinkwatch.apk"

prop() { sed -n "s/^$1=//p" keystore.properties | tail -1; }

[ -f keystore.properties ] || {
    echo "keystore.properties missing — refusing to publish a build that would be debug-signed" >&2
    exit 1
}

./scripts/build.sh assembleRelease
[ -f "$APK" ] || {
    echo "no APK produced: a release build needs keystore.properties and the key it names" >&2
    exit 1
}

VERSION_CODE="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
VERSION_NAME="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || {
    echo "cannot read versionCode/versionName out of $APK" >&2
    exit 1
}

# The signer the APK actually carries. apksigner exits non-zero on anything it cannot
# verify, which is also a refusal to publish.
APK_SHA256="$("$APKSIGNER" verify --print-certs "$APK" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"
[ -n "$APK_SHA256" ] || {
    echo "refusing to publish: $APK has no verifiable signer (unsigned or corrupt)" >&2
    exit 1
}

KEYSTORE="$(prop storeFile)"
[ -n "$KEYSTORE" ] && [ -f "$KEYSTORE" ] || {
    echo "refusing to publish: release keystore ${KEYSTORE:-<unset in keystore.properties>} not found" >&2
    exit 1
}
RELEASE_SHA256="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" \
    -storepass "$(prop storePassword)" -alias "$(prop keyAlias)" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
[ -n "$RELEASE_SHA256" ] || {
    echo "refusing to publish: cannot read the certificate for $(prop keyAlias) out of $KEYSTORE" >&2
    exit 1
}
if [ "$APK_SHA256" != "$RELEASE_SHA256" ]; then
    echo "refusing to publish: $APK is signed by $APK_SHA256, not the release key $RELEASE_SHA256" >&2
    echo "(a build without keystore.properties used to be debug-signed and shipped as-is)" >&2
    exit 1
fi

# The server must move forward: the app only offers an update when versionCode is
# greater than the installed one, so an equal or lower code is a silent non-release.
python3 - "$APK_DIR/version.json" "$VERSION_CODE" <<'PY'
import json, sys

target, code = sys.argv[1], int(sys.argv[2])
try:
    with open(target) as handle:
        published = int(json.load(handle).get("versionCode", 0))
except FileNotFoundError:
    published = 0
except (ValueError, TypeError, AttributeError) as error:
    sys.exit(f"refusing to publish: {target} is unreadable ({error})")
if code <= published:
    sys.exit(f"refusing to publish versionCode {code}: the server already advertises {published}")
PY

mkdir -p "$APK_DIR"
TMP_APK="$(mktemp "$APK_DIR/.$APK_NAME.XXXXXX")"
TMP_JSON="$(mktemp "$APK_DIR/.version.json.XXXXXX")"
trap 'rm -f "$TMP_APK" "$TMP_JSON"' EXIT

cp "$APK" "$TMP_APK"
chmod 0644 "$TMP_APK" "$TMP_JSON"

python3 - "$TMP_JSON" "$VERSION_CODE" "$VERSION_NAME" "$NOTES" <<'PY'
import json, os, sys

target, code, name, notes = sys.argv[1:5]
with open(target, "w") as handle:
    json.dump({"versionCode": int(code), "versionName": name, "notes": notes}, handle)
    handle.flush()
    os.fsync(handle.fileno())
PY

# Both files are complete on disk and still invisible under their temporary names. The
# APK goes first so the bytes a new version.json points at are already there; the
# version.json rename is the commit point for the pair.
python3 - "$TMP_APK" "$APK_DIR/$APK_NAME" "$TMP_JSON" "$APK_DIR/version.json" <<'PY'
import os, sys

apk_tmp, apk, meta_tmp, meta = sys.argv[1:5]
with open(apk_tmp, "rb+") as handle:
    os.fsync(handle.fileno())
os.replace(apk_tmp, apk)
os.replace(meta_tmp, meta)
print(f"published {meta} via {apk}")
PY

cat "$APK_DIR/version.json"
echo
