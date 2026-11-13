# Offline ASR integration scaffold

The service is intended to run in the isolated `:asr` process and uses Messenger IPC so the IME can communicate across the process boundary. The manifest fragment declares the service as non-exported. Microphone permission is intentionally not requested until the actual capture implementation and permission activity are ready.

This is an IPC/model-validation scaffold, not functioning dictation. It currently:
- accepts a start message with a model path,
- checks that the model file exists and is non-empty,
- returns an explicit not-integrated error,
- accepts a stop message placeholder.

It does not capture audio, run VAD, load Whisper, or transcribe speech. Do not expose a microphone button or claim dictation works yet.

## Integration sequence

1. Select and record an exact reviewed whisper.cpp commit.
2. Review whisper.cpp and all native dependency licenses.
3. Build a CPU-only JNI library for arm64; keep inference out of the IME process.
4. Add a legally distributable, hash-verified quantized model. Do not download it from the app.
5. Implement a transparent permission activity, AudioRecord, VAD, partial/final stabilization, cancellation, cleanup and device benchmarks.
6. Add `RECORD_AUDIO` only with the completed permission UX and tests.
7. Test offline operation, thermal behavior, memory, process restart and sensitive-field blocking.
