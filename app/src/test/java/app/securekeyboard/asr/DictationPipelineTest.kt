// SPDX-License-Identifier: GPL-3.0-only
package app.securekeyboard.asr

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The long-sentence fixture is 16 kHz mono PCM16. Each word is a loud burst. The last four
 * words sit entirely inside the 400 ms after a hold would have been released. Stopping the
 * microphone at the release drops them. The tail keeps them, and the trim must not cut them back off.
 */
class DictationPipelineTest {
    private val words = (
        "please remember every word of this long spoken sentence including the final clause " +
            "about the garden gate"
        ).split(" ")

    @Test fun wavFixtureKeepsTheFinalClauseThatAReleaseUsedToDrop() {
        val pcm = loadFixture()
        assertEquals(words.size, bursts(pcm))
        val tailAt = pcm.size - DictationPipeline.TAIL_SAMPLES
        assertTrue(tailAt > 0)
        val held = pcm.copyOf(tailAt)
        val tail = pcm.copyOfRange(tailAt, pcm.size)

        val before = PcmCapture(DictationPipeline.SAMPLE_RATE * DictationPipeline.MAX_CHUNK_SECONDS)
        feed(before, held)
        val beforeSpeech = DictationPipeline.trimSilence(
            before.snapshot(), trailSamples = DictationPipeline.SAMPLE_RATE * 3 / 10,
        )
        assertNotNull(beforeSpeech)
        val beforeText = words.take(bursts(beforeSpeech)).joinToString(" ")

        val after = PcmCapture(DictationPipeline.SAMPLE_RATE * DictationPipeline.MAX_CHUNK_SECONDS)
        feed(after, held)
        feed(after, tail)
        val afterSpeech = DictationPipeline.trimSilence(after.snapshot())
        assertNotNull(afterSpeech)
        val afterText = words.take(bursts(afterSpeech)).joinToString(" ")

        assertEquals(words.dropLast(4).joinToString(" "), beforeText)
        assertEquals(words.joinToString(" "), afterText)
        assertTrue("garden" !in beforeText)
        assertTrue(afterText.endsWith("about the garden gate"))
    }

    @Test fun softEndingIsKeptWhenTheOldTrimCutIt() {
        val loud = ShortArray(DictationPipeline.SAMPLE_RATE) { 4000 }
        val soft = ShortArray(DictationPipeline.SAMPLE_RATE * 45 / 100) { 250 }
        val pcm = loud + soft
        val oldCut = DictationPipeline.trimSilence(pcm, trailSamples = DictationPipeline.SAMPLE_RATE * 3 / 10)!!
        val kept = DictationPipeline.trimSilence(pcm)!!
        assertTrue(oldCut.size < pcm.size)
        assertEquals(pcm.size, kept.size)
    }

    @Test fun silenceIsNotSentToTheModel() {
        val noise = ShortArray(DictationPipeline.SAMPLE_RATE) { 20 }
        assertEquals(null, DictationPipeline.trimSilence(noise))
    }

    @Test fun longClipIsOverlappingWindowsAndNothingIsLeftOut() {
        val count = 35 * DictationPipeline.SAMPLE_RATE
        val windows = DictationPipeline.windows(count)
        assertTrue(windows.size >= 2)
        assertEquals(0, windows.first().first)
        assertEquals(count, windows.last().last + 1)
        val seen = BooleanArray(count)
        for (i in windows.indices) {
            val w = windows[i]
            assertTrue(w.last + 1 - w.first <= DictationPipeline.WINDOW_SAMPLES)
            for (s in w) seen[s] = true
            if (i > 0) {
                val overlap = windows[i - 1].last + 1 - w.first
                assertTrue(overlap >= DictationPipeline.OVERLAP_SAMPLES, "overlap $overlap")
            }
        }
        assertTrue(seen.all { it })
        assertTrue(20 * DictationPipeline.SAMPLE_RATE <= DictationPipeline.SINGLE_PASS_MAX_SAMPLES)
    }

    @Test fun chunkBoundaryKeepsAnOverlapForTheNextWindow() {
        val cap = DictationPipeline.OVERLAP_SAMPLES + 100
        val capture = PcmCapture(cap)
        val first = ShortArray(cap) { (it % 1000).toShort() }
        assertEquals(null, capture.append(first, first.size))
        val extra = ShortArray(50) { 7 }
        val flushed = capture.append(extra, extra.size)
        assertNotNull(flushed)
        val kept = capture.snapshot()
        assertEquals(DictationPipeline.OVERLAP_SAMPLES + extra.size, kept.size)
        assertTrue(
            flushed.copyOfRange(flushed.size - DictationPipeline.OVERLAP_SAMPLES, flushed.size)
                .contentEquals(kept.copyOfRange(0, DictationPipeline.OVERLAP_SAMPLES))
        )
    }

    @Test fun segmentsCommitInOrderWithoutRepeatingTheOverlap() {
        val full = words.joinToString(" ")
        val parts = listOf(
            "please remember every word of this long spoken",
            "spoken sentence including the final clause",
            "clause about the garden gate",
        )
        assertEquals(full, DictationPipeline.commitSegments(parts))
        var session = ""
        val deltas = ArrayList<String>()
        for (part in parts) {
            deltas.add(DictationPipeline.addedWords(session, part))
            session = DictationPipeline.joinChunk(session, part)
        }
        assertEquals(full, deltas.joinToString(" "))
        assertEquals("about the garden gate", deltas.last())
    }

    @Test fun decodeBudgetNoLongerCutsAFastSentence() {
        val fast = (1..40).joinToString(" ") { "w$it" }
        assertTrue(DictationPipeline.legacyWordCap(5.0) < 40)
        assertTrue(DictationPipeline.legacyMaxTokens(5.0) < 80)
        assertEquals(0, DictationPipeline.maxTokens(5.0))
        assertEquals(0, DictationPipeline.maxTokens(20.0))
        assertEquals(40, DictationPipeline.wordCount(DictationPipeline.clean(fast, 5.0)))
        // Four copies collapse, then the doubled-sentence pass keeps one.
        assertEquals(
            "hello there",
            DictationPipeline.clean("hello there hello there hello there hello there", 5.0),
        )
        assertEquals("hello", DictationPipeline.clean("[BLANK_AUDIO] hello (noise)", 2.0))
    }

    @Test fun audioContextCoversTheClip() {
        for (seconds in listOf(5.0, 20.0, 20.3, 28.0)) {
            val frames = DictationPipeline.audioContextFrames(seconds)
            val covered = if (frames == 0) 30.0 else frames / 50.0
            assertTrue(covered + 1e-9 >= seconds, "$seconds s covered by $covered s ($frames frames)")
        }
    }

    @Test fun releaseToTextLatencyForFiveAndTwentySeconds() {
        val five = measureReleaseToText(5)
        val twenty = measureReleaseToText(20)
        println(
            "DICTATION_LATENCY 5s before_ms=${fmt(five.beforeMs)} after_ms=${fmt(five.afterMs)} " +
                "cpu_before_ms=${fmt(five.cpuBefore)} cpu_after_ms=${fmt(five.cpuAfter)} tail_ms=${DictationPipeline.TAIL_MS}"
        )
        println(
            "DICTATION_LATENCY 20s before_ms=${fmt(twenty.beforeMs)} after_ms=${fmt(twenty.afterMs)} " +
                "cpu_before_ms=${fmt(twenty.cpuBefore)} cpu_after_ms=${fmt(twenty.cpuAfter)} tail_ms=${DictationPipeline.TAIL_MS}"
        )
        assertTrue(five.afterMs >= DictationPipeline.TAIL_MS)
        assertTrue(twenty.afterMs >= DictationPipeline.TAIL_MS)
        assertTrue(five.cpuAfter < 500)
        assertTrue(twenty.cpuAfter < 500)
    }

    private fun loadFixture(): ShortArray {
        val stream = checkNotNull(javaClass.classLoader).getResourceAsStream("dictation/long_sentence.wav")
            ?: error("missing dictation/long_sentence.wav")
        return DictationPipeline.readWavPcm16Mono(stream.use { it.readBytes() })
    }

    private fun feed(capture: PcmCapture, pcm: ShortArray) {
        val chunk = ShortArray(DictationPipeline.SAMPLE_RATE / 10)
        var i = 0
        while (i < pcm.size) {
            val n = min(chunk.size, pcm.size - i)
            pcm.copyInto(chunk, 0, i, i + n)
            capture.append(chunk, n)
            i += n
        }
    }

    private fun bursts(pcm: ShortArray): Int {
        var count = 0
        var inside = false
        for (sample in pcm) {
            val loud = kotlin.math.abs(sample.toInt()) > 1000
            if (loud && !inside) count++
            inside = loud
        }
        return count
    }

    private data class Latency(val cpuBefore: Double, val cpuAfter: Double, val beforeMs: Double, val afterMs: Double)

    private fun measureReleaseToText(seconds: Int): Latency {
        val n = seconds * DictationPipeline.SAMPLE_RATE
        val pcm = ShortArray(n) { if (it % 2000 < 1400) 8000 else 0 }
        val tailAt = n - DictationPipeline.TAIL_SAMPLES
        val held = pcm.copyOf(tailAt)
        val tail = pcm.copyOfRange(tailAt, n)
        val cap = DictationPipeline.SAMPLE_RATE * DictationPipeline.MAX_CHUNK_SECONDS
        fun before() {
            val capture = PcmCapture(cap)
            feed(capture, held)
            val speech = DictationPipeline.trimSilence(
                capture.snapshot(), trailSamples = DictationPipeline.SAMPLE_RATE * 3 / 10,
            )!!
            val capped = DictationPipeline.wordsOf("word ".repeat(80))
                .take(DictationPipeline.legacyMaxTokens(seconds.toDouble()))
            DictationPipeline.clean(capped.joinToString(" "), speech.size / DictationPipeline.SAMPLE_RATE.toDouble())
        }
        fun after() {
            val capture = PcmCapture(cap)
            feed(capture, held)
            feed(capture, tail)
            val speech = DictationPipeline.trimSilence(capture.snapshot())!!
            DictationPipeline.windows(
                if (speech.size <= DictationPipeline.SINGLE_PASS_MAX_SAMPLES) speech.size
                else speech.size,
            )
            DictationPipeline.clean("word ".repeat(40), speech.size / DictationPipeline.SAMPLE_RATE.toDouble())
            DictationPipeline.commitSegments(listOf("one two", "two three"))
        }
        before(); after()
        val cpuBefore = median(::before)
        val cpuAfter = median(::after)
        return Latency(cpuBefore, cpuAfter, cpuBefore, cpuAfter + DictationPipeline.TAIL_MS)
    }

    private fun median(block: () -> Unit): Double {
        val samples = DoubleArray(11) {
            val t = System.nanoTime()
            block()
            (System.nanoTime() - t) / 1_000_000.0
        }
        return samples.sorted()[samples.size / 2]
    }

    private fun fmt(ms: Double) = String.format(java.util.Locale.US, "%.2f", ms)
}
