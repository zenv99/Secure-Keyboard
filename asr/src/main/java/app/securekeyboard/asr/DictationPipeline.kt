package app.securekeyboard.asr

import kotlin.math.ceil
import kotlin.math.min

/**
 * On-device dictation capture and text assembly. No Android types, so the release tail, the
 * silence trim, and the long-clip windows can be tested without a microphone or a model.
 *
 * Audio is 16 kHz mono PCM16, which is what whisper.cpp, the stream example, and the Android
 * example all consume. Speech boundaries use an energy VAD (20 ms frames). That stays inside
 * the app process: no Silero or WebRTC model is shipped.
 */
object DictationPipeline {
    const val SAMPLE_RATE = 16_000
    const val TAIL_MS = 400
    const val TAIL_SAMPLES = SAMPLE_RATE * TAIL_MS / 1000
    const val TAIL_NS = TAIL_MS * 1_000_000L
    /** Overlap kept when a hold crosses a whisper window, so a word on the cut is in both clips. */
    const val OVERLAP_SAMPLES = SAMPLE_RATE
    const val MAX_CHUNK_SECONDS = 28
    const val WINDOW_SAMPLES = SAMPLE_RATE * 25
    /** At or under this length one whisper pass sees the whole clip (the model window is 30 s). */
    const val SINGLE_PASS_MAX_SAMPLES = SAMPLE_RATE * 29
    const val VAD_WINDOW = SAMPLE_RATE / 50
    const val VAD_MEAN_ABS = 300
    const val TRIM_LEAD_SAMPLES = SAMPLE_RATE / 2
    /** Was 300 ms. 500 ms keeps a soft final syllable and the release tail. */
    const val TRIM_TRAIL_SAMPLES = SAMPLE_RATE / 2

    fun wordsOf(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun wordCount(text: String) = wordsOf(text).size

    fun norm(word: String) = word.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Encoder frames that cover [seconds] of audio. 0 means the full 30 s model window.
     * 50 frames per second. A window shorter than the clip drops the ending.
     * Floor of 640 (12.8 s): tighter windows previously looped on short clips.
     * Keep this in sync with whisper_jni.cpp.
     */
    fun audioContextFrames(seconds: Double): Int {
        val frames = ceil(seconds * 50.0).toInt() + 50
        val ctx = ((frames + 63) / 64) * 64
        val covered = maxOf(640, ctx)
        return if (covered >= 1500) 0 else covered
    }

    /** Previous budget: about 6 tokens per second, hard-capped. 0 in the new path means no cap. */
    fun legacyMaxTokens(seconds: Double) = min(220, maxOf(16, (seconds * 6.0).toInt() + 8))

    fun maxTokens(@Suppress("UNUSED_PARAMETER") seconds: Double) = 0

    fun legacyWordCap(seconds: Double) = (seconds * 4.0).toInt() + 6

    fun wordCap(seconds: Double) = (seconds * 8.0).toInt() + 16

    /** Last few words only. A long prompt makes the next window repeat itself. */
    fun carryPrompt(committed: String): String = wordsOf(committed).takeLast(8).joinToString(" ")

    /**
     * Append [next] after [previous], dropping a repeated word run from the one-second overlap.
     */
    fun joinChunk(previous: String, next: String): String {
        val a = wordsOf(previous)
        val b = wordsOf(next)
        if (a.isEmpty()) return b.joinToString(" ")
        if (b.isEmpty()) return a.joinToString(" ")
        var overlap = min(min(a.size, b.size), 8)
        while (overlap > 0) {
            val same = (0 until overlap).all { norm(a[a.size - overlap + it]) == norm(b[it]) }
            if (same) break
            overlap--
        }
        return (a + b.drop(overlap)).joinToString(" ")
    }

    fun addedWords(previous: String, chunkText: String): String {
        val prev = wordsOf(previous)
        val merged = wordsOf(joinChunk(previous, chunkText))
        return merged.drop(prev.size).joinToString(" ")
    }

    fun commitSegments(parts: List<String>): String {
        var acc = ""
        for (part in parts) acc = joinChunk(acc, part)
        return acc
    }

    /**
     * Drops bracketed noise tags, collapses a phrase repeated more than twice, and refuses a
     * word rate no person speaks. The cap keeps a fast sentence; it only cuts a runaway decode.
     */
    fun clean(raw: String, seconds: Double): String {
        val noTags = raw.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ")
        var words = wordsOf(noTags)
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
        if (words.size >= 4 && words.size % 2 == 0) {
            val half = words.size / 2
            if ((0 until half).all { norm(words[it]) == norm(words[it + half]) }) words = words.take(half)
        }
        val cap = wordCap(seconds)
        if (words.size > cap) words = words.take(cap)
        return words.joinToString(" ")
    }

    fun voicedSeconds(pcm: ShortArray): Double {
        var loud = 0
        var i = 0
        while (i + VAD_WINDOW <= pcm.size) {
            if (loudWindow(pcm, i)) loud++
            i += VAD_WINDOW
        }
        return loud * 0.02
    }

    /**
     * Keeps [leadSamples] before the first loud frame and [trailSamples] after the last.
     * Null when the clip is only background noise (whisper would invent a word).
     */
    fun trimSilence(
        pcm: ShortArray,
        trailSamples: Int = TRIM_TRAIL_SAMPLES,
        leadSamples: Int = TRIM_LEAD_SAMPLES,
    ): ShortArray? {
        var first = 0
        while (first < pcm.size - VAD_WINDOW && !loudWindow(pcm, first)) first += VAD_WINDOW
        if (first >= pcm.size - VAD_WINDOW) return null
        var last = pcm.size
        while (last > first + VAD_WINDOW && !loudWindow(pcm, last - VAD_WINDOW)) last -= VAD_WINDOW
        val from = maxOf(0, first - leadSamples)
        val to = min(pcm.size, last + trailSamples)
        return pcm.copyOfRange(from, to)
    }

    /**
     * Overlapping windows for a clip that would not fit in one 30 s whisper pass.
     * Each range is exclusive at the end. A short clip is a single window.
     */
    fun windows(
        sampleCount: Int,
        windowSamples: Int = WINDOW_SAMPLES,
        overlapSamples: Int = OVERLAP_SAMPLES,
    ): List<IntRange> {
        if (sampleCount <= 0) return emptyList()
        if (sampleCount <= windowSamples) return listOf(0 until sampleCount)
        val step = maxOf(1, windowSamples - overlapSamples)
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < sampleCount) {
            val end = min(sampleCount, start + windowSamples)
            out.add(start until end)
            if (end == sampleCount) break
            start += step
        }
        return out
    }

    fun readWavPcm16Mono(bytes: ByteArray): ShortArray {
        require(bytes.size > 44) { "wav too small" }
        fun text(at: Int) = bytes.decodeToString(at, at + 4)
        require(text(0) == "RIFF" && text(8) == "WAVE") { "not a wav" }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = text(pos)
            val size = le32(bytes, pos + 4)
            val start = pos + 8
            if (start + size > bytes.size) break
            if (id == "fmt ") {
                channels = le16(bytes, start + 2)
                rate = le32(bytes, start + 4)
                bits = le16(bytes, start + 14)
            } else if (id == "data") {
                val n = size / 2
                data = ShortArray(n) { i ->
                    val o = start + i * 2
                    ((bytes[o].toInt() and 0xff) or (bytes[o + 1].toInt() shl 8)).toShort()
                }
            }
            pos = start + size + (size and 1)
        }
        require(channels == 1 && rate == SAMPLE_RATE && bits == 16) {
            "need 16 kHz mono PCM16, got $rate Hz $channels ch $bits bit"
        }
        return data ?: error("wav has no data chunk")
    }

    private fun loudWindow(pcm: ShortArray, start: Int): Boolean {
        val end = min(pcm.size, start + VAD_WINDOW)
        if (end <= start) return false
        var sum = 0L
        for (i in start until end) sum += kotlin.math.abs(pcm[i].toInt())
        return sum / (end - start) > VAD_MEAN_ABS
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or (b[at + 1].toInt() shl 8)

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
}

/**
 * PCM gathered while the mic key is held, plus the short read after release.
 * The microphone is closed by the caller once [snapshot] has been taken.
 */
class PcmCapture(capacity: Int) {
    private var buffer = ShortArray(capacity)
    var count = 0
        private set

    /**
     * Copies [read] samples from the start of [incoming].
     * Returns a finished chunk when the buffer would pass one whisper window, keeping
     * [DictationPipeline.OVERLAP_SAMPLES] so the next chunk still contains the boundary.
     */
    fun append(incoming: ShortArray, read: Int): ShortArray? {
        var flushed: ShortArray? = null
        if (count + read > buffer.size) {
            flushed = buffer.copyOf(count)
            val keep = min(count, DictationPipeline.OVERLAP_SAMPLES)
            val next = ShortArray(buffer.size)
            if (keep > 0) System.arraycopy(buffer, count - keep, next, 0, keep)
            buffer.fill(0)
            buffer = next
            count = keep
        }
        System.arraycopy(incoming, 0, buffer, count, read)
        count += read
        return flushed
    }

    fun snapshot(): ShortArray = buffer.copyOf(count)

    fun wipe() {
        buffer.fill(0)
        count = 0
    }
}
