package app.securekeyboard.asr

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
 * Privacy: audio exists only in memory while the key is held, plus a 400 ms tail after release,
 * and is dropped after transcription. The microphone is closed as soon as that tail is read.
 * Neither audio nor recognised text is ever written to disk or to the log.
 */
class AsrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var recorder: Recorder? = null // main thread only
    private var startToken = 0 // newest press; older retry loops give up
    private var pendingStart = false // MSG_START received, microphone not open yet (main thread only)
    private var pendingClient: Messenger? = null
    private var whisper: WhisperBridge? = null // worker thread only

    private val idleUnload = Runnable { worker.execute { closeWhisper() } }

    private val incoming = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START -> { pendingStart = true; startToken++; handleStart(message.replyTo, startToken); true }
            MSG_STOP -> {
                // Released before the microphone was even opened (fast tap / retry window): cancel that start, otherwise
                // the recorder would start later with nobody left to stop it and keep the microphone busy.
                if (pendingStart) {
                    pendingStart = false
                    pendingClient?.let { reply(it, MSG_FINAL, last = true, meta = "released before the microphone opened") }
                    pendingClient = null
                }
                recorder?.stop()
                true
            }
            MSG_WARM -> { worker.execute { try { ensureWhisper() } catch (_: AsrException) { } }; true }
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

    private fun handleStart(client: Messenger?, token: Int, attempt: Int = 0) {
        if (client == null) return
        if (!pendingStart || token != startToken) return // released or superseded while waiting: this start was cancelled
        pendingClient = client
        if (recorder != null) {
            // The previous recording is still closing (pressed again within a moment): retry shortly instead of
            // silently ignoring this press.
            if (attempt < 8) main.postDelayed({ handleStart(client, token, attempt + 1) }, 100)
            else { pendingStart = false; pendingClient = null; reply(client, MSG_ERROR, code = ERR_AUDIO) }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = false; pendingClient = null
            reply(client, MSG_ERROR, code = ERR_NO_PERMISSION)
            return
        }
        main.removeCallbacks(idleUnload)
        pendingStart = false
        pendingClient = null
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
        // Worker thread only. Previous windows of this hold, used as a short prompt. Never logged.
        private var sessionText = ""

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
            val capture = PcmCapture(SAMPLE_RATE * MAX_CHUNK_SECONDS)
            var total = 0L
            var failed = false
            try {
                record.startRecording()
                reply(client, MSG_STARTED) // the microphone is really open now: the user may speak
                while (running && !failed) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read < 0) { failed = true; break }
                    if (read == 0) continue
                    capture.append(chunk, read)?.let { submit(it, last = false) }
                    total += read
                    if (total > SAMPLE_RATE.toLong() * MAX_HOLD_SECONDS) running = false // stuck-key safety
                }
                // Finger is up, but AudioRecord.stop() discards samples the HAL already captured,
                // and the last syllable is often still arriving. Keep the mic for the tail only.
                if (!cancelled && !failed) {
                    val deadline = System.nanoTime() + DictationPipeline.TAIL_NS
                    while (!cancelled && System.nanoTime() < deadline) {
                        val read = record.read(chunk, 0, chunk.size)
                        if (read < 0) break // keep what we already have
                        if (read == 0) continue
                        capture.append(chunk, read)?.let { submit(it, last = false) }
                    }
                }
            } catch (_: Exception) {
                failed = true
            } finally {
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
            main.post { if (recorder === this) recorder = null }
            when {
                cancelled -> capture.wipe()
                failed -> reply(client, MSG_ERROR, code = ERR_AUDIO)
                else -> submit(capture.snapshot(), last = true)
            }
            capture.wipe()
        }

        private fun submit(pcm: ShortArray, last: Boolean) {
            worker.execute {
                var text = ""
                var meta = "clip=" + String.format(java.util.Locale.US, "%.1f", pcm.size / SAMPLE_RATE.toDouble()) + "s too-short"
                val startedAt = System.currentTimeMillis()
                if (pcm.size >= MIN_SAMPLES) {
                    try {
                        val engine = ensureWhisper()
                        val silent = pcm.all { it.toInt() == 0 }
                        // Energy VAD: drop leading and trailing silence, but keep a soft ending.
                        val speech = DictationPipeline.trimSilence(pcm)
                        if (silent) {
                            meta = "all-zero audio (mic blocked)"
                            reply(client, MSG_ERROR, code = ERR_SILENT)
                        } else if (speech == null) {
                            // nothing but background noise: Whisper would invent words ("you"), so type nothing
                            meta = "only background noise, clip=" + String.format(java.util.Locale.US, "%.1f", pcm.size / SAMPLE_RATE.toDouble()) + "s"
                            if (last && sessionText.isBlank()) reply(client, MSG_ERROR, code = ERR_NO_SPEECH)
                        } else {
                            val prompt = DictationPipeline.carryPrompt(sessionText)
                            val tModel = System.currentTimeMillis()
                            text = transcribeSpeech(engine, speech, prompt)
                            val delta = DictationPipeline.addedWords(sessionText, text)
                            sessionText = DictationPipeline.joinChunk(sessionText, text)
                            text = delta
                            meta = String.format(java.util.Locale.US, "clip=%.1fs voiced=%.1fs words=%d model-wait=%dms transcribe=%dms",
                                pcm.size / SAMPLE_RATE.toDouble(), DictationPipeline.voicedSeconds(speech), DictationPipeline.wordCount(text),
                                tModel - startedAt, System.currentTimeMillis() - tModel)
                            if (text.isBlank() && last && sessionText.isBlank()) reply(client, MSG_ERROR, code = ERR_NO_SPEECH)
                        }
                    } catch (e: AsrException) {
                        reply(client, MSG_ERROR, code = e.code)
                    } catch (_: Exception) {
                        // A decode failure must still end the session. The keyboard shows the generic error.
                        reply(client, MSG_ERROR, code = ERR_AUDIO)
                    }
                }
                pcm.fill(0)
                if (last) sessionText = ""
                reply(client, MSG_FINAL, text = text, last = last, meta = meta)
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

    /**
     * One whisper pass when the clip fits in the 30 s window. Longer audio is overlapping
     * windows with the previous text as a short prompt, then joined in order.
     */
    private fun transcribeSpeech(engine: WhisperBridge, speech: ShortArray, prompt: String): String {
        val ranges = if (speech.size <= DictationPipeline.SINGLE_PASS_MAX_SAMPLES) listOf(0 until speech.size)
            else DictationPipeline.windows(speech.size)
        var text = transcribeRanges(engine, speech, ranges, prompt)
        val seconds = speech.size / SAMPLE_RATE.toDouble()
        val voiced = DictationPipeline.voicedSeconds(speech)
        if (ranges.size == 1 && seconds >= 1.5 && DictationPipeline.wordCount(text) < voiced * 0.7) {
            val body = normalizedFloats(speech)
            val lead = FloatArray(SAMPLE_RATE * 3 / 10)
            val pad = FloatArray(SAMPLE_RATE / 2)
            val padded = pad + lead + body + pad
            body.fill(0f)
            val retry = DictationPipeline.clean(
                engine.transcribe(padded, LANGUAGE, threads(), prompt), seconds,
            )
            padded.fill(0f)
            if (DictationPipeline.wordCount(retry) > DictationPipeline.wordCount(text)) text = retry
        }
        if (ranges.size == 1 && seconds >= 6.0 && DictationPipeline.wordCount(text) < voiced) {
            val pieces = transcribeRanges(
                engine, speech,
                DictationPipeline.windows(speech.size, SAMPLE_RATE * 10, DictationPipeline.OVERLAP_SAMPLES),
                prompt,
            )
            if (DictationPipeline.wordCount(pieces) > DictationPipeline.wordCount(text)) text = pieces
        }
        return text
    }

    private fun transcribeRanges(
        engine: WhisperBridge, pcm: ShortArray, ranges: List<IntRange>, prompt: String,
    ): String {
        var committed = ""
        for (range in ranges) {
            val piece = pcm.copyOfRange(range.first, range.last + 1)
            if (piece.size < MIN_SAMPLES) continue
            val trimmed = DictationPipeline.trimSilence(piece) ?: continue
            val seconds = trimmed.size / SAMPLE_RATE.toDouble()
            // 0.3 s of silence in front: a first word that starts at the clip edge is often dropped.
            val body = normalizedFloats(trimmed)
            val floats = FloatArray(SAMPLE_RATE * 3 / 10) + body
            body.fill(0f)
            val carry = DictationPipeline.carryPrompt(if (committed.isBlank()) prompt else committed)
            var text = DictationPipeline.clean(engine.transcribe(floats, LANGUAGE, threads(), carry), seconds)
            floats.fill(0f)
            if (voicedEnough(trimmed) && DictationPipeline.wordCount(text) == 1 && text.trim().length == 1 &&
                text.trim().lowercase() !in listOf("a", "i")) text = ""
            if (text.isNotBlank()) committed = DictationPipeline.joinChunk(committed, text)
            piece.fill(0)
        }
        return committed
    }

    private fun voicedEnough(pcm: ShortArray) = DictationPipeline.voicedSeconds(pcm) >= 1.0

    /** Samples as floats, with quiet recordings amplified (up to 8x) so Whisper gets a normal level. */
    private fun normalizedFloats(pcm: ShortArray): FloatArray {
        var peak = 1
        for (v in pcm) { val a = kotlin.math.abs(v.toInt()); if (a > peak) peak = a }
        val gain = if (peak < 11000) minOf(8f, 22000f / peak) else 1f
        return FloatArray(pcm.size) { (pcm[it] * gain).coerceIn(-32767f, 32767f) / 32768f }
    }

    // worker thread only
    private fun closeWhisper() {
        whisper?.close()
        whisper = null
    }

    // whisper.cpp's stream example uses min(4, cores). Capping here keeps a short decode on the
    // big cores instead of waking every little core. Explicit affinity is not set.
    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun reply(target: Messenger, what: Int, text: String = "", code: Int = 0, last: Boolean = false, meta: String = "") {
        val response = Message.obtain(null, what)
        response.data = Bundle().apply {
            putString(KEY_TEXT, text)
            putInt(KEY_CODE, code)
            putBoolean(KEY_LAST, last || what == MSG_ERROR)
            putString(KEY_META, meta)
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
        const val MSG_WARM = 3 // load the model now so the first dictation is not slow
        const val MSG_FINAL = 4
        const val MSG_ERROR = 5
        const val MSG_STARTED = 6 // recording has really begun

        const val KEY_TEXT = "text"
        const val KEY_CODE = "code"
        const val KEY_LAST = "last"
        const val KEY_META = "meta" // timings and counts only, never text

        const val ERR_NO_PERMISSION = 1
        const val ERR_NO_MODEL = 2
        const val ERR_MODEL_LOAD = 3
        const val ERR_AUDIO = 4
        const val ERR_NATIVE = 5
        const val ERR_MODEL_INTEGRITY = 6
        const val ERR_SILENT = 7 // microphone delivered only silence (blocked by the OS)
        const val ERR_NO_SPEECH = 8 // audio was fine but nothing was recognised

        private const val SAMPLE_RATE = DictationPipeline.SAMPLE_RATE
        private const val MAX_CHUNK_SECONDS = DictationPipeline.MAX_CHUNK_SECONDS
        private const val MAX_HOLD_SECONDS = 300
        private const val MIN_SAMPLES = SAMPLE_RATE / 4 // ignore accidental taps under 250 ms
        private const val IDLE_UNLOAD_MS = 600_000L
        private const val LANGUAGE = "en"
    }
}
