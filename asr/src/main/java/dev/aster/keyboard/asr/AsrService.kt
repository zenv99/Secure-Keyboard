package dev.aster.keyboard.asr

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.util.concurrent.Executors

/**
 * Push-to-talk speech recognition, fully on device, in its own process (":asr").
 *
 * Protocol (Messenger): client sends MSG_START with replyTo set, holds, then sends MSG_STOP.
 * The service replies with one MSG_FINAL per audio chunk (KEY_TEXT, KEY_LAST) or MSG_ERROR (KEY_CODE).
 * The last reply of a session always has KEY_LAST = true, so the client can always reset.
 *
 * Privacy: audio exists only in memory while the key is held and is dropped after transcription.
 * Neither audio nor recognised text is ever written to disk or to the log.
 */
class AsrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var recorder: Recorder? = null // main thread only
    private var whisper: WhisperBridge? = null // worker thread only

    private val idleUnload = Runnable { worker.execute { closeWhisper() } }

    private val incoming = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START -> { handleStart(message.replyTo); true }
            MSG_STOP -> { recorder?.stop(); true }
            else -> false
        }
    }
    private val messenger by lazy { Messenger(incoming) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Client is gone (keyboard closed or killed): never keep the microphone open.
        recorder?.cancel()
        return false
    }

    private fun handleStart(client: Messenger?) {
        if (client == null || recorder != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reply(client, MSG_ERROR, code = ERR_NO_PERMISSION)
            return
        }
        main.removeCallbacks(idleUnload)
        val created = try {
            Recorder(client)
        } catch (_: Exception) {
            reply(client, MSG_ERROR, code = ERR_AUDIO)
            return
        }
        recorder = created
        // Load the model while the user is still speaking, to hide start-up latency.
        worker.execute { try { ensureWhisper() } catch (_: AsrException) { /* reported when transcribing */ } }
        created.start()
    }

    private inner class Recorder(private val client: Messenger) {
        private val record: AudioRecord
        @Volatile private var running = true
        @Volatile private var cancelled = false

        init {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IllegalStateException()
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException()
            }
        }

        fun start() {
            Thread({ loop() }, "asr-record").start()
        }

        fun stop() { running = false }

        fun cancel() { cancelled = true; running = false }

        private fun loop() {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            var buffer = ShortArray(SAMPLE_RATE * MAX_CHUNK_SECONDS)
            var count = 0
            var total = 0L
            var failed = false
            try {
                record.startRecording()
                while (running) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) { failed = true; break }
                    if (read == 0) continue
                    if (count + read > buffer.size) {
                        // Whisper handles up to 30 s per window: hand off the full chunk and keep recording.
                        submit(buffer.copyOf(count), last = false)
                        buffer = ShortArray(SAMPLE_RATE * MAX_CHUNK_SECONDS)
                        count = 0
                    }
                    System.arraycopy(chunk, 0, buffer, count, read)
                    count += read
                    total += read
                    if (total > SAMPLE_RATE.toLong() * MAX_HOLD_SECONDS) running = false // stuck-key safety
                }
            } catch (_: Exception) {
                failed = true
            } finally {
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
            main.post { if (recorder === this) recorder = null }
            when {
                cancelled -> buffer.fill(0)
                failed -> reply(client, MSG_ERROR, code = ERR_AUDIO)
                else -> submit(buffer.copyOf(count), last = true)
            }
            buffer.fill(0)
        }

        private fun submit(pcm: ShortArray, last: Boolean) {
            worker.execute {
                var text = ""
                if (pcm.size >= MIN_SAMPLES) {
                    try {
                        val engine = ensureWhisper()
                        val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
                        text = engine.transcribe(floats, LANGUAGE, threads()).trim()
                        floats.fill(0f)
                    } catch (e: AsrException) {
                        reply(client, MSG_ERROR, code = e.code)
                    }
                }
                pcm.fill(0)
                reply(client, MSG_FINAL, text = text, last = last)
                main.removeCallbacks(idleUnload)
                main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
            }
        }
    }

    // worker thread only
    private fun ensureWhisper(): WhisperBridge {
        whisper?.let { return it }
        if (!WhisperBridge.available) throw AsrException(ERR_NATIVE)
        val file = ModelStore(this).ensure()
        val engine = WhisperBridge()
        if (!engine.load(file.path)) throw AsrException(ERR_MODEL_LOAD)
        whisper = engine
        return engine
    }

    // worker thread only
    private fun closeWhisper() {
        whisper?.close()
        whisper = null
    }

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun reply(target: Messenger, what: Int, text: String = "", code: Int = 0, last: Boolean = false) {
        val response = Message.obtain(null, what)
        response.data = Bundle().apply {
            putString(KEY_TEXT, text)
            putInt(KEY_CODE, code)
            putBoolean(KEY_LAST, last || what == MSG_ERROR)
        }
        try {
            target.send(response)
        } catch (_: RemoteException) {
            // client disconnected; never retain recognised text
        }
    }

    override fun onDestroy() {
        recorder?.cancel()
        worker.execute { closeWhisper() }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_FINAL = 4
        const val MSG_ERROR = 5

        const val KEY_TEXT = "text"
        const val KEY_CODE = "code"
        const val KEY_LAST = "last"

        const val ERR_NO_PERMISSION = 1
        const val ERR_NO_MODEL = 2
        const val ERR_MODEL_LOAD = 3
        const val ERR_AUDIO = 4
        const val ERR_NATIVE = 5
        const val ERR_MODEL_INTEGRITY = 6

        private const val SAMPLE_RATE = 16_000
        private const val MAX_CHUNK_SECONDS = 28
        private const val MAX_HOLD_SECONDS = 300
        private const val MIN_SAMPLES = SAMPLE_RATE / 4 // ignore accidental taps under 250 ms
        private const val IDLE_UNLOAD_MS = 45_000L
        private const val LANGUAGE = "en"
    }
}
