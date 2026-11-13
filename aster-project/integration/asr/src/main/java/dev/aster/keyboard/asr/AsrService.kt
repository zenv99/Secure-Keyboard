package dev.aster.keyboard.asr

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-isolated ASR seam only. No audio capture or transcription is implemented.
 * Do not wire into the app until the native engine and lifecycle are complete.
 */
class AsrService : Service() {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): AsrService = this@AsrService
    }

    private val binder = LocalBinder()
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = RemoteCallbackList<Listener>()

    override fun onBind(intent: Intent?): IBinder = binder

    fun register(listener: Listener) { listeners.register(listener) }
    fun unregister(listener: Listener) { listeners.unregister(listener) }

    fun start(modelFile: File) {
        worker.execute {
            if (!modelFile.isFile || modelFile.length() <= 0L) {
                notifyError("ASR model is missing or empty")
                return@execute
            }
            // TODO: verify SHA-256, initialize JNI, then implement AudioRecord and VAD.
            notifyError("Offline ASR is not integrated; microphone capture remains disabled")
        }
    }

    fun stop() {
        worker.execute {
            // TODO: stop capture, flush segment and release native context.
        }
    }

    private fun notifyError(message: String) {
        val count = listeners.beginBroadcast()
        try {
            for (i in 0 until count) listeners.getBroadcastItem(i).onError(message)
        } finally {
            listeners.finishBroadcast()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        listeners.kill()
        super.onDestroy()
    }
}
