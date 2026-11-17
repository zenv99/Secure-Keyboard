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
                reply(client, MSG_STARTED) // the microphone is really open now: the user may speak
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
                        val silent = pcm.all { it.toInt() == 0 }
                        // Whisper invents / repeats words when a clip is mostly silence: keep just the speech (+200 ms).
                        val speech = trimSilence(pcm)
                        if (silent) {
                            reply(client, MSG_ERROR, code = ERR_SILENT)
                        } else if (speech == null) {
                            // nothing but background noise: Whisper would invent words ("you"), so type nothing
                            if (last) reply(client, MSG_ERROR, code = ERR_NO_SPEECH)
                        } else {
                            // 0.3 s of silence in front: Whisper often drops or garbles a first word that starts right at the clip edge.
                            val body = normalizedFloats(speech)
                            val floats = FloatArray(SAMPLE_RATE * 3 / 10) + body
                            body.fill(0f)
                            val seconds = speech.size / SAMPLE_RATE.toDouble()
                            text = clean(engine.transcribe(floats, LANGUAGE, threads()), seconds)
                            // Far too few words for the amount of speech (e.g. a whole sentence came back as "x"):
                            // try once more with a little silence around the audio and keep the fuller result.
                            if (seconds >= 1.5 && wordCount(text) < voicedSeconds(speech) * 0.7) {
                                val pad = FloatArray(SAMPLE_RATE / 2)
                                val padded = pad + floats + pad
                                val retry = clean(engine.transcribe(padded, LANGUAGE, threads()), seconds)
                                padded.fill(0f)
                                if (wordCount(retry) > wordCount(text)) text = retry
                            }
                            // A lone stray letter for a clip with real speech is a failed transcription, not what was said.
                            if (voicedSeconds(speech) >= 1.0 && wordCount(text) == 1 && text.trim().length == 1 &&
                                text.trim().lowercase() !in listOf("a", "i")) text = ""
                            floats.fill(0f)
                            // Long clips occasionally come back far too short (the decoder stops after a few words).
                            // Real speech has well over one word per second of voiced audio: if not, redo it in
                            // pieces cut at the pauses, which short clips handle reliably, and keep the fuller result.
                            if (seconds >= 6.0 && wordCount(text) < voicedSeconds(speech)) {
                                val piecesText = transcribeInPieces(engine, speech)
                                if (wordCount(piecesText) > wordCount(text)) text = piecesText
                            }
                            if (text.isBlank() && last) reply(client, MSG_ERROR, code = ERR_NO_SPEECH)
                        }
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

    /** Drops bracketed noise tags and collapses runaway repetition ("a a a a a ..."), keeping real doubles. */
    private fun clean(raw: String, seconds: Double): String {
        val noTags = raw.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
        var words: List<String> = noTags.split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (n in 1..40) {
            if (n * 2 > words.size) break
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                var reps = 1
                if (i + n <= words.size) {
                    while (i + (reps + 1) * n <= words.size &&
                        (0 until n).all { norm(words[i + it]) == norm(words[i + reps * n + it]) }) reps++
                }
                if (reps > 3) {
                    repeat(2) { r -> for (k in 0 until n) out.add(words[i + r * n + k]) }
                    i += reps * n
                } else {
                    out.add(words[i]); i++
                }
            }
            words = out
        }
        // The whole sentence said twice in a row ("A B C A B C"): keep one copy.
        if (words.size >= 4 && words.size % 2 == 0) {
            val half = words.size / 2
            if ((0 until half).all { norm(words[it]) == norm(words[it + half]) }) words = words.take(half)
        }
        // Final hard cap: nobody speaks faster than ~4 words per second.
        val maxWords = (seconds * 4.0).toInt() + 6
        if (words.size > maxWords) words = words.take(maxWords)
        return words.joinToString(" ").trim()
    }

    /** The speech part of the clip (+200 ms), or null when the clip contains no speech at all. */
    private fun trimSilence(pcm: ShortArray): ShortArray? {
        val win = SAMPLE_RATE / 50 // 20 ms
        fun loud(start: Int): Boolean {
            val end = minOf(pcm.size, start + win)
            if (end <= start) return false
            var sum = 0L
            for (i in start until end) sum += kotlin.math.abs(pcm[i].toInt())
            return sum / (end - start) > 300
        }
        var first = 0
        while (first < pcm.size - win && !loud(first)) first += win
        if (first >= pcm.size - win) return null
        var last = pcm.size
        while (last > first + win && !loud(last - win)) last -= win
        val margin = SAMPLE_RATE / 5
        val from = maxOf(0, first - margin)
        val to = minOf(pcm.size, last + margin)
        return if (to - from < MIN_SAMPLES) pcm else pcm.copyOfRange(from, to)
    }

    /** Samples as floats, with quiet recordings amplified (up to 8x) so Whisper gets a normal level. */
    private fun normalizedFloats(pcm: ShortArray): FloatArray {
        var peak = 1
        for (v in pcm) { val a = kotlin.math.abs(v.toInt()); if (a > peak) peak = a }
        val gain = if (peak < 11000) minOf(8f, 22000f / peak) else 1f
        return FloatArray(pcm.size) { (pcm[it] * gain).coerceIn(-32767f, 32767f) / 32768f }
    }

    private fun wordCount(t: String) = t.split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Seconds of the clip that are clearly louder than background noise. */
    private fun voicedSeconds(pcm: ShortArray): Double {
        val win = SAMPLE_RATE / 50
        var loudWindows = 0
        var i = 0
        while (i + win <= pcm.size) {
            var sum = 0L
            for (k in i until i + win) sum += kotlin.math.abs(pcm[k].toInt())
            if (sum / win > 300) loudWindows++
            i += win
        }
        return loudWindows * 0.02
    }

    /** Transcribes the clip as pieces of at most ~10 s, each cut at the quietest moment (a pause). */
    private fun transcribeInPieces(engine: WhisperBridge, pcm: ShortArray): String {
        val win = SAMPLE_RATE / 50 // 20 ms
        val windows = pcm.size / win
        val energy = LongArray(windows) { w ->
            var sum = 0L
            for (k in w * win until (w + 1) * win) sum += kotlin.math.abs(pcm[k].toInt())
            sum
        }
        val cuts = ArrayList<Int>()
        var start = 0 // in windows
        while (windows - start > 500) { // more than 10 s left
            var bestAt = start + 350
            var best = Long.MAX_VALUE
            for (w in start + 200..start + 480) { // cut somewhere between 4 s and 9.6 s
                var e = 0L
                for (k in w until w + 5) e += energy[k] // ~100 ms of quiet
                if (e < best) { best = e; bestAt = w + 2 }
            }
            cuts.add(bestAt * win)
            start = bestAt
        }
        val bounds = listOf(0) + cuts + listOf(pcm.size)
        val out = ArrayList<String>()
        for (n in 0 until bounds.size - 1) {
            val piece = pcm.copyOfRange(bounds[n], bounds[n + 1])
            if (piece.size < MIN_SAMPLES) continue
            val trimmed = trimSilence(piece) ?: continue
            val floats = FloatArray(trimmed.size) { trimmed[it] / 32768f }
            val t = clean(engine.transcribe(floats, LANGUAGE, threads()), trimmed.size / SAMPLE_RATE.toDouble())
            floats.fill(0f)
            if (t.isNotBlank()) out.add(t)
        }
        return out.joinToString(" ")
    }

    private fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

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
        const val MSG_WARM = 3 // load the model now so the first dictation is not slow
        const val MSG_FINAL = 4
        const val MSG_ERROR = 5
        const val MSG_STARTED = 6 // recording has really begun

        const val KEY_TEXT = "text"
        const val KEY_CODE = "code"
        const val KEY_LAST = "last"

        const val ERR_NO_PERMISSION = 1
        const val ERR_NO_MODEL = 2
        const val ERR_MODEL_LOAD = 3
        const val ERR_AUDIO = 4
        const val ERR_NATIVE = 5
        const val ERR_MODEL_INTEGRITY = 6
        const val ERR_SILENT = 7 // microphone delivered only silence (blocked by the OS)
        const val ERR_NO_SPEECH = 8 // audio was fine but nothing was recognised

        private const val SAMPLE_RATE = 16_000
        private const val MAX_CHUNK_SECONDS = 28
        private const val MAX_HOLD_SECONDS = 300
        private const val MIN_SAMPLES = SAMPLE_RATE / 4 // ignore accidental taps under 250 ms
        private const val IDLE_UNLOAD_MS = 600_000L
        private const val LANGUAGE = "en"
    }
}
