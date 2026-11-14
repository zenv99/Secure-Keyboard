#!/usr/bin/env bash
# Fetches the whisper.cpp ggml model into the app assets (build time only; the app itself has no
# network permission) and verifies its SHA-256 against aster-project/asr/MODEL_SHA256.
#
# While MODEL_SHA256 contains UNPINNED the script refuses to run unless ALLOW_UNPINNED=1, and then
# prints the computed hash so you can review it and commit it as the pin.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODEL_NAME="ggml-base.en-q5_1.bin"
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${MODEL_NAME}"
PIN="$(tr -d '[:space:]' < "$ROOT/aster-project/asr/MODEL_SHA256" | tr '[:upper:]' '[:lower:]')"
DEST="$ROOT/app/src/main/assets/asr"
mkdir -p "$DEST"

if [[ "$PIN" == "unpinned" && "${ALLOW_UNPINNED:-0}" != "1" ]]; then
  echo "MODEL_SHA256 is UNPINNED. Review a download, then commit its hash (or set ALLOW_UNPINNED=1)." >&2
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
if [[ -f "$DEST/model.bin" && -f "$DEST/model.sha256" ]]; then
  echo "Model already present: $(cat "$DEST/model.sha256")"
  ACTUAL="$(cat "$DEST/model.sha256")"
else
  curl -fL --retry 3 -o "$TMP" "$URL"
  ACTUAL="$(sha256sum "$TMP" | cut -d' ' -f1)"
  if [[ "$PIN" != "unpinned" && "$ACTUAL" != "$PIN" ]]; then
    echo "FAIL: model hash $ACTUAL does not match pinned $PIN" >&2
    exit 1
  fi
  mv "$TMP" "$DEST/model.bin"
  printf '%s\n' "$ACTUAL" > "$DEST/model.sha256"
fi

if [[ "$PIN" == "unpinned" ]]; then
  echo "::warning::Model is UNPINNED. Computed SHA-256: $ACTUAL (${MODEL_NAME}). Commit it to aster-project/asr/MODEL_SHA256."
  [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && echo "Model ${MODEL_NAME} SHA-256: \`$ACTUAL\`" >> "$GITHUB_STEP_SUMMARY"
elif [[ "$ACTUAL" != "$PIN" ]]; then
  echo "FAIL: cached model hash $ACTUAL does not match pinned $PIN" >&2
  exit 1
fi
echo "OK: $MODEL_NAME sha256=$ACTUAL"
