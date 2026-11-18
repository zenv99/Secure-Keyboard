#!/usr/bin/env bash
# Runs inside the emulator: installs the debug keyboard, makes it the active keyboard, runs the smoke tests.
set -euo pipefail
PKG=app.securekeyboard.keyboard.debug
./gradlew --no-daemon :app:installDebug
IME="$(adb shell ime list -a -s | tr -d '\r' | grep "$PKG" | head -1)"
echo "IME: $IME"
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
./gradlew --no-daemon :app:connectedDebugAndroidTest
