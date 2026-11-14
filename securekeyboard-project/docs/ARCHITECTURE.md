# Architecture

## Runtime boundaries

The existing HeliBoard IME remains the primary app and keyboard process. Dictation is planned as an isolated Android service process:

```text
IME process
  ├─ HeliBoard input method and keyboard UI
  ├─ existing dictionary and candidate engine
  ├─ conservative correction policy
  ├─ local personalization store
  └─ Binder client
       |
       v
separate :asr process
  ├─ AudioRecord capture (16 kHz mono)
  ├─ voice activity detection and segmentation
  ├─ JNI boundary
  └─ whisper.cpp CPU inference
```

The ASR service must not block the IME process. Marshal callbacks onto the IME main thread before using InputConnection. Stop capture and release native resources on cancellation, unbind, process shutdown, and idle timeout.

## Autocorrect

Use layered scoring, not a single edit-distance threshold:

1. Touch likelihood from key geometry and distance from key centres.
2. Candidate generation from the existing dictionary/native engine, with adjacency-aware weighted edits.
3. Context ranking over preceding tokens.
4. Conservative acceptance policy with immediate undo/revert.
5. Protection for known proper nouns, contractions, and sensitive fields.

Keep expensive inference off the keystroke path. Measure before introducing a neural reranker.

## Dictation

Integrate whisper.cpp through JNI at an exact reviewed commit. Start with CPU-only arm64 and benchmark quantized tiny/base models on physical devices. No in-app model download. Add VAD, permission UX, stable partials, cancellation, model hash verification, and thermal/memory soak tests before enabling production dictation.

## Budgets (targets, not claims)

- Candidate generation p95 under 5 ms on a named mid-range device.
- No synchronous disk I/O or neural inference on the input event path.
- Report dictation real-time factor and end-of-utterance latency by device/model.
