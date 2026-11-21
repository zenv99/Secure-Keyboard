#!/usr/bin/env bash
# Two release-signed APKs above v1.0.6 (versionCode 4434), plus the published v1.0.6 APK.
# Same signing key is required so adb install -r is an update, not a new install.
set -euo pipefail
OUT=release-apks
mkdir -p "$OUT"
PW="$(printf '%s' "${SK_KEYSTORE_PASSWORD:-}" | tr -d '[:space:]')"
if [ -z "${SK_KEYSTORE_B64:-}" ] || ! printf '%s' "$SK_KEYSTORE_B64" | tr -d '[:space:]' | base64 -d > "$RUNNER_TEMP/sk.jks" 2>/dev/null; then
  echo "Release signing key is missing. Refusing to build an APK that cannot update v1.0.6." >&2
  exit 1
fi
export SK_KEYSTORE_FILE="$RUNNER_TEMP/sk.jks"
export SK_KEYSTORE_PASSWORD="$PW"
export SK_VERSION_NAME="1.0.6"
AAPT="${ANDROID_HOME}/build-tools/37.0.0/aapt"
APKSIGNER="${ANDROID_HOME}/build-tools/37.0.0/apksigner"
EXPECTED=33eacd63355ee1b19abca1f997ea25a7946960bbf934411691273dde053c78d4

build_one() {
  local code="$1"
  local dest="$2"
  rm -rf app/build/generated/source/buildConfig app/build/outputs/apk/release
  export SK_VERSION_CODE="$code"
  ./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
  local apk
  apk="$(echo app/build/outputs/apk/release/*.apk)"
  cp "$apk" "$dest"
  local got
  got="$("$AAPT" dump badging "$dest" | sed -n 's/.*versionCode='\''\([0-9]*\)'\''.*/\1/p')"
  test "$got" = "$code"
  echo "built $dest versionCode=$got"
}

build_one 4435 "$OUT/sk-4435.apk"
build_one 4436 "$OUT/sk-4436.apk"
gh release download v1.0.6 --repo zenv99/Secure-Keyboard --pattern 'SecureKeyboard-v1.0.6.apk' --dir "$OUT" --clobber

for apk in "$OUT/sk-4435.apk" "$OUT/sk-4436.apk" "$OUT/SecureKeyboard-v1.0.6.apk"; do
  digest="$("$APKSIGNER" verify --print-certs "$apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
  echo "$apk cert $digest"
  test "$digest" = "$EXPECTED"
done
