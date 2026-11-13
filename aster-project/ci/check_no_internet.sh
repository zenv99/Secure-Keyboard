#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 <apk> [<apk> ...]" >&2; exit 2; fi
for apk in "$@"; do
  [[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }
  if command -v apkanalyzer >/dev/null 2>&1; then
    manifest="$(apkanalyzer manifest permissions "$apk")"
  elif command -v aapt >/dev/null 2>&1; then
    manifest="$(aapt dump permissions "$apk")"
  else
    echo "Neither apkanalyzer nor aapt is available; refusing to skip privacy check." >&2; exit 2
  fi
  if grep -Fq "android.permission.INTERNET" <<<"$manifest"; then
    echo "FAIL: INTERNET permission found in $apk" >&2; exit 1
  fi
  echo "PASS: no INTERNET permission in $apk"
done
