# Native ASR TODO

Planned JNI seam:

- `nativeCreate(modelPath, threads): Long`
- `nativeTranscribe(handle, pcm16kMono): String`
- `nativeCancel(handle)`
- `nativeDestroy(handle)`

Validate model paths and hashes, reject invalid handles, catch native failures at the service boundary, and test repeated create/cancel/destroy cycles. Do not log raw audio or recognized text. Streaming partials and VAD follow only after one-shot transcription is stable.
