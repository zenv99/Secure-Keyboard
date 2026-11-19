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
                                                           jint threads, jstring prompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(pcm);
    // One Whisper window is 30 s. Reject anything larger instead of allocating without a bound.
    constexpr jsize kMaxSamples = 16000 * 32;
    if (n <= 0 || n > kMaxSamples) return env->NewStringUTF("");
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return env->NewStringUTF("");
    const char *language = env->GetStringUTFChars(lang, nullptr);
    const char *promptChars = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

    // Greedy, one candidate. Beam search costs a lot more battery for a short dictation.
    // Temperature still falls back when the decode looks degenerate (stream example default).
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, (int) threads);
    params.language = language;
    params.translate = false;
    // The context object is reused across holds. Do not feed it the previous hold's tokens.
    // A short initial_prompt carries the previous window of THIS hold only.
    params.no_context = true;
    params.initial_prompt = (promptChars != nullptr && promptChars[0] != '\0') ? promptChars : nullptr;
    params.carry_initial_prompt = false;
    params.single_segment = false;
    // Timestamps off: turning them on duplicated text. Every segment is still concatenated below.
    params.no_timestamps = true;
    const double seconds = (double) n / 16000.0;
    // Cover the whole clip. A shorter audio_ctx drops the end of the audio.
    // 50 encoder frames per second, plus 1 s, rounded up, and never under 12.8 s
    // (tighter windows previously caused repeated-word loops). 0 means the full 30 s window.
    // Keep this in sync with DictationPipeline.audioContextFrames.
    {
        const int frames = (int) std::ceil(seconds * 50.0) + 50;
        const int ctx = ((frames + 63) / 64) * 64;
        const int covered = std::max(640, ctx);
        params.audio_ctx = covered >= 1500 ? 0 : covered;
    }
    // 0 = no per-segment cap. With no_timestamps, a positive cap ends the segment and
    // whisper.cpp then skips the rest of the window, which cut off the rest of the sentence.
    params.max_tokens = 0;
    params.temperature = 0.0f;
    params.temperature_inc = 0.2f;
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

    if (promptChars != nullptr) env->ReleaseStringUTFChars(prompt, promptChars);
    env->ReleaseStringUTFChars(lang, language);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_app_securekeyboard_asr_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

} // extern "C"
