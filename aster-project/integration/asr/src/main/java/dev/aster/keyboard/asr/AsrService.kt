package dev.aster.keyboard.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-isolated ASR IPC scaffold only.
 *
 * This service deliberately does not request microphone access, capture audio,
 * load a native model, or transcribe speech. It validates the IPC/model seam
 * before those higher-risk pieces are integrated and tested.
 *
 * Protocol: clients bind with Messenger, send MSG_START with KEY_MODEL_PATH
 * and replyTo set to their Messenger, then receive MSG_PARTIAL, MSG_FINAL,
 * or MSG_ERROR. MSG_STOP cancels future work once capture is implemented.
 */
class AsrService : Service() {
    private val worker = Executors.newSingleThreadExecutor()

    private val incoming = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START -> {
                val replyTo = message.replyTo
                val modelPath = message.data.getString(KEY_MODEL_PATH)
                worker.execute {
                    if (modelPath.isNullOrBlank()) {
                        reply(replyTo, MSG_ERROR, "Model path was not supplied")
                    } else {
                        val model = File(modelPath)
                        if (!model.isFile || model.length() <= 0L) {
                            reply(replyTo, MSG_ERROR, "ASR model is missing or empty")
                        } else {
                            // TODO: verify SHA-256, initialize pinned whisper.cpp JNI,
                            // then add AudioRecord, VAD and segmented inference.
                            reply(replyTo, MSG_ERROR,
                                "Offline ASR is not integrated; microphone capture remains disabled")
                        }
                    }
                }
                true
            }
            MSG_STOP -> {
                worker.execute {
                    // TODO: stop AudioRecord, cancel inference and release native context.
                }
                true
            }
            else -> false
        }
    }

    private val messenger by lazy { Messenger(incoming) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun reply(target: Messenger?, what: Int, text: String) {
        if (target == null) return
        val response = Message.obtain(null, what)
        response.data = Bundle().apply { putString(KEY_TEXT, text) }
        try {
            target.send(response)
        } catch (_: RemoteException) {
            // Client disconnected; never retain or log recognized text.
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_PARTIAL = 3
        const val MSG_FINAL = 4
        const val MSG_ERROR = 5
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_TEXT = "text"
    }
}
