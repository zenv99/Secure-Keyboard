package app.securekeyboard.asr

/** Thin wrapper around the JNI bridge to the pinned whisper.cpp. Not thread-safe: use from one thread. */
internal class WhisperBridge {
    private var handle = 0L

    fun load(modelPath: String): Boolean {
        close()
        handle = nativeInit(modelPath)
        return handle != 0L
    }

    fun transcribe(pcm: FloatArray, language: String, threads: Int, prompt: String = ""): String =
        if (handle == 0L) "" else nativeTranscribe(handle, pcm, language, threads, prompt)

    fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, language: String, threads: Int, prompt: String,
    ): String
    private external fun nativeFree(handle: Long)

    companion object {
        /** False when the native library is missing (e.g. unsupported ABI); the service reports ERR_NATIVE. */
        val available: Boolean = try {
            System.loadLibrary("securekeyboard_whisper")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
