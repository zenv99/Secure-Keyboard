// Narrow JNI bridge to the pinned whisper.cpp. Never logs audio or transcribed text.
#include <jni.h>
#include <string>
#include <cstdlib>
#include <algorithm>
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
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    const char *language = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, (int) threads);
    params.language = language;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.no_timestamps = true;
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
