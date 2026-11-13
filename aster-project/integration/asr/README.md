# Offline ASR integration scaffold

This is intentionally not wired into the shipping app yet. It describes a safe integration seam, not functioning dictation.

1. Select and record an exact reviewed whisper.cpp commit.
2. Review whisper.cpp and all native dependency licenses.
3. Build a CPU-only arm64 JNI library; keep native inference out of the IME process.
4. Add a legally distributable, hash-verified quantized model. Do not download it from the app.
5. Implement microphone permission UX, AudioRecord, VAD, partial/final stabilization, cancellation, cleanup and device benchmarks.
6. Test offline operation, thermal behavior, memory, process restart and sensitive-field blocking.

The service sample validates a model file exists but intentionally does not capture audio or transcribe speech. Do not enable microphone UI until the complete lifecycle is implemented and tested.
