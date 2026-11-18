// Narrow JNI bridge to the pinned whisper.cpp. Never logs audio or transcribed text.
#include <jni.h>
#include <string>
#include <cstdlib>
#include <algorithm>
#include <cmath>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_securekeyboard_asr_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring path) {
    // ggml can fork()/exec a debugger to print a backtrace when it aborts; never allow that.
    setenv("GGML_NO_BACKTRACE", "1", 1);
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false; // CPU only: deterministic, no vendor driver code in the privacy surface
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_app_securekeyboard_asr_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject, jlong handle,
                                                           jfloatArray pcm, jstring lang,
                                                           jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(pcm);
    // One Whisper window is 30 s. Reject anything larger instead of allocating without a bound.
    constexpr jsize kMaxSamples = 16000 * 32;
    if (n <= 0 || n > kMaxSamples) return env->NewStringUTF("");
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");
    const char *language = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, (int) threads);
    params.language = language;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    // Same decoding setup as the first version that worked (timestamps off: turning them on duplicated text).
    params.no_timestamps = true;
    const double seconds = (double) n / 16000.0;
    // Size the audio window to the clip instead of always encoding 30 s: much faster for short dictation.
    // Generous margin (4 s) and a floor of 12.8 s: tighter windows caused repeated-word loops before.
    if (seconds <= 20.0) {
        const int ctx = (int) (std::ceil((seconds + 4.0) * 50.0 / 64.0) * 64.0);
        params.audio_ctx = std::min(1500, std::max(640, ctx));
    }
    // Hard limit on output: about 6 tokens per second of speech at most.
    params.max_tokens = std::min(220, std::max(16, (int) (seconds * 6.0) + 8));
    params.temperature_inc = 0.2f;      // standard fallback when output looks degenerate
    params.entropy_thold = 2.4f;
    params.logprob_thold = -1.0f;
    params.no_speech_thold = 0.6f;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.greedy.best_of = 1;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;

    std::string out;
    if (whisper_full(ctx, params, samples, n) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text != nullptr) out += text;
        }
    }

    env->ReleaseStringUTFChars(lang, language);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_app_securekeyboard_asr_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

} // extern "C"
