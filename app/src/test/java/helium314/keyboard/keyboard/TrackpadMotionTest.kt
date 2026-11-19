// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Motion numbers below are measured by running this test, not guessed.
 * Density 2.75 is a 440 dpi phone. Sensitivity 50 is the default (1x scale).
 * Slow movement is about one character per 10 dp, after a short dead zone.
 */
class TrackpadMotionTest {
    @Test fun slowDragNeverJumpsMoreThanOneCharacterPerFrame() {
        val slow60 = stream(dxPxPerFrame = 2f, frames = 120, frameNs = 16_666_667L)
        val slow120 = stream(dxPxPerFrame = 1f, frames = 240, frameNs = 8_333_333L)
        assertTrue(slow60.maxGraphemes <= 1, "60 Hz max step ${slow60.maxGraphemes}")
        assertTrue(slow120.maxGraphemes <= 1, "120 Hz max step ${slow120.maxGraphemes}")
        assertTrue(slow60.totalGraphemes > 0)
        assertTrue(slow120.totalGraphemes > 0)
        // Same finger speed (about 120 px/s) should move a similar number of characters.
        val ratio = slow120.totalGraphemes.toFloat() / slow60.totalGraphemes
        assertTrue(ratio in 0.7f..1.4f, "60 Hz ${slow60.totalGraphemes} vs 120 Hz ${slow120.totalGraphemes}")
    }

    @Test fun fastFlickMovesFurtherThanTheSameDistanceDoneSlowly() {
        val slow = stream(dxPxPerFrame = 2f, frames = 80, frameNs = 16_666_667L)
        val fast = stream(dxPxPerFrame = 16f, frames = 10, frameNs = 8_333_333L)
        assertEquals(160, slow.frames * 2) // 80 * 2 px
        assertEquals(160, fast.frames * 16)
        assertTrue(fast.totalGraphemes > slow.totalGraphemes * 2,
            "fast ${fast.totalGraphemes} should be more than twice slow ${slow.totalGraphemes}")
    }

    @Test fun diagonalMovesBothAxes() {
        val motion = motion()
        var gx = 0
        var gy = 0
        var t = 0L
        repeat(40) {
            motion.offer(8f, 8f, t)
            val steps = motion.consume(t + 8_333_333L)
            gx += steps.graphemes
            gy += steps.lines
            t += 8_333_333L
        }
        assertTrue(gx > 0, "horizontal $gx")
        assertTrue(gy > 0, "vertical $gy")
    }

    @Test fun oneFrameOfCoalescedSamplesDoesNotDoubleCount() {
        val motion = motion()
        motion.offer(4f, 0f, 0L)
        motion.offer(4f, 0f, 2_000_000L)
        val once = motion.consume(8_333_333L)
        val again = motion.consume(16_000_000L)
        assertEquals(0, again.graphemes)
        assertTrue(abs(once.graphemes) <= 1)
        assertEquals(8.333333f, once.latencyMs, 0.01f)
    }

    @Test fun addedFrameWaitIsTheOnlyLatency() {
        val motion = motion()
        val samples = ArrayList<Float>()
        var t = 1_000_000_000L
        val frame = 8_333_333L
        repeat(120) {
            motion.offer(3f, 0f, t)
            val steps = motion.consume(t + frame)
            samples.add(steps.latencyMs)
            t += frame
        }
        val p50 = percentile(samples, 0.50f)
        val p95 = percentile(samples, 0.95f)
        println("TRACKPAD_LATENCY_MS hz=120 p50=$p50 p95=$p95 frame=8.333 n=${samples.size}")
        assertEquals(8.333f, p50, 0.05f)
        assertTrue(p95 <= 8.4f, "p95 $p95")

        val motion60 = motion()
        val samples60 = ArrayList<Float>()
        var t60 = 1_000_000_000L
        val frame60 = 16_666_667L
        repeat(120) {
            motion60.offer(3f, 0f, t60)
            samples60.add(motion60.consume(t60 + frame60).latencyMs)
            t60 += frame60
        }
        val p50_60 = percentile(samples60, 0.50f)
        val p95_60 = percentile(samples60, 0.95f)
        println("TRACKPAD_LATENCY_MS hz=60 p50=$p50_60 p95=$p95_60 frame=16.667 n=${samples60.size}")
        assertEquals(16.667f, p50_60, 0.05f)
        assertTrue(p95_60 <= 16.8f, "60 Hz p95 $p95_60")
    }

    @Test fun consumeItselfIsWellUnderAFrame() {
        val motion = motion()
        val times = LongArray(2000)
        var t = 0L
        repeat(times.size) { i ->
            motion.offer(1.5f, -0.4f, t)
            val start = System.nanoTime()
            motion.consume(t + 1_000_000L)
            times[i] = System.nanoTime() - start
            t += 8_333_333L
        }
        val sorted = times.sorted()
        val p50us = sorted[sorted.size / 2] / 1_000.0
        val p95us = sorted[(sorted.size * 0.95f).toInt()] / 1_000.0
        println("TRACKPAD_COMPUTE_US p50=$p50us p95=$p95us n=${times.size}")
        assertTrue(p95us < 200.0, "compute p95 ${p95us}us")
    }

    @Test fun jitterDoesNotMoveTheCaret() {
        val jitter = jitterStream(frames = 400, amplitudePx = 3f)
        val moved = play(motion(), jitter)
        assertEquals(0, moved.graphemes, "horizontal jitter walked ${moved.graphemes}")
        assertEquals(0, moved.lines, "vertical jitter walked ${moved.lines}")
    }

    @Test fun shakyDragDoesNotAddMovesBeyondTheNetTravel() {
        // +3 px, -1 px, repeating. Net travel is +1 px per sample. The old 5.5 dp model
        // turned the extra back-and-forth into additional characters.
        val shaky = ArrayList<Sample>()
        var t = 0L
        val frame = 8_333_333L
        repeat(160) { i ->
            val dx = if (i % 2 == 0) 3f else -1f
            val dy = if (i % 2 == 0) 0.8f else -0.8f
            shaky.add(Sample(dx, dy, t))
            t += frame
        }
        val before = legacyMoves(shaky)
        val after = play(motion(), shaky)
        println("TRACKPAD_MOVES shaky_before_graphemes=${before.graphemes} shaky_after_graphemes=${after.graphemes} " +
            "shaky_before_lines=${before.lines} shaky_after_lines=${after.lines}")
        assertTrue(after.graphemes < before.graphemes,
            "shaky drag after ${after.graphemes} should be under the old ${before.graphemes}")
        assertTrue(after.lines <= before.lines)
        assertEquals(0, after.lines)
    }

    @Test fun sameSlowDragMovesFewerCharactersThanTheOldSensitiveModel() {
        val drag = streamSamples(dxPxPerFrame = 2f, frames = 120, frameNs = 16_666_667L)
        val before = legacyMoves(drag)
        val after = play(motion(), drag)
        println("TRACKPAD_MOVES slow_before_graphemes=${before.graphemes} slow_after_graphemes=${after.graphemes}")
        assertTrue(before.graphemes > after.graphemes)
        assertTrue(after.graphemes > 0)
        assertTrue(after.maxGraphemes <= 1)
    }

    @Test fun verticalNeedsMoreTravelThanHorizontal() {
        val horizontal = play(motion(), streamSamples(dxPxPerFrame = 8f, dyPxPerFrame = 0f, frames = 40, frameNs = 8_333_333L))
        val vertical = play(motion(), streamSamples(dxPxPerFrame = 0f, dyPxPerFrame = 8f, frames = 40, frameNs = 8_333_333L))
        assertTrue(horizontal.graphemes > vertical.lines,
            "same 8 px stream moved ${horizontal.graphemes} characters but ${vertical.lines} lines")
    }

    @Test fun cursorCrossesLinesAndKeepsItsColumn() {
        val doc = TrackpadDocument("hello", "\nworld\n!")
        assertEquals(5, doc.charOffset()) // after "hello", before the newline
        assertEquals(1, doc.move(1, 0))
        assertEquals(6, doc.charOffset()) // stepped across the newline onto the next line
        val down = doc.move(0, 1)
        assertTrue(down >= 1)
        val text = "hello\nworld\n!"
        assertEquals('!', text[doc.charOffset()])

        val column = TrackpadDocument("a", "\ncd")
        column.move(0, 1)
        assertEquals(3, column.charOffset()) // one character into "cd"
        assertEquals('d', "a\ncd"[column.charOffset()])
    }

    private fun motion(): TrackpadMotion = TrackpadMotion().apply { configure(2.75f, 50) }

    private data class StreamResult(val totalGraphemes: Int, val maxGraphemes: Int, val frames: Int)

    private data class Sample(val dx: Float, val dy: Float, val timeNs: Long)

    private data class MoveCount(val graphemes: Int, val lines: Int, val maxGraphemes: Int)

    private fun stream(dxPxPerFrame: Float, frames: Int, frameNs: Long): StreamResult {
        val played = play(motion(), streamSamples(dxPxPerFrame, 0f, frames, frameNs))
        return StreamResult(played.graphemes, played.maxGraphemes, frames)
    }

    private fun streamSamples(dxPxPerFrame: Float, dyPxPerFrame: Float = 0f, frames: Int, frameNs: Long): List<Sample> {
        val out = ArrayList<Sample>(frames)
        var t = 0L
        repeat(frames) {
            out.add(Sample(dxPxPerFrame, dyPxPerFrame, t))
            t += frameNs
        }
        return out
    }

    private fun jitterStream(frames: Int, amplitudePx: Float): List<Sample> {
        val out = ArrayList<Sample>(frames)
        var t = 0L
        val frame = 8_333_333L
        repeat(frames) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            out.add(Sample(sign * amplitudePx, sign * amplitudePx * 0.5f, t))
            t += frame
        }
        return out
    }

    private fun play(motion: TrackpadMotion, samples: List<Sample>): MoveCount {
        var gx = 0
        var gy = 0
        var maxStep = 0
        for (i in samples.indices) {
            val sample = samples[i]
            val applyAt = if (i + 1 < samples.size) samples[i + 1].timeNs else sample.timeNs + 8_333_333L
            motion.offer(sample.dx, sample.dy, sample.timeNs)
            val steps = motion.consume(applyAt)
            gx += steps.graphemes
            gy += steps.lines
            if (abs(steps.graphemes) > maxStep) maxStep = abs(steps.graphemes)
        }
        return MoveCount(gx, gy, maxStep)
    }

    /**
     * The previous model: one character per 5.5 dp, acceleration from 60 dp/s, no dead zone
     * and no smoothing. Kept so a shaky stream can be compared with the numbers it used to emit.
     */
    private fun legacyMoves(samples: List<Sample>): MoveCount {
        var fracX = 0f
        var fracY = 0f
        var last = 0L
        var gx = 0
        var gy = 0
        val density = 2.75f
        val scale = 1f
        for (sample in samples) {
            val dt = if (last == 0L) 8_000_000L else (sample.timeNs - last).coerceIn(1_000_000L, 50_000_000L)
            last = sample.timeNs
            val dxDp = sample.dx / density
            val dyDp = sample.dy / density
            val speed = hypot(dxDp, dyDp) / (dt / 1_000_000_000f)
            val accel = (1f + (speed - 60f).coerceAtLeast(0f) / 500f).coerceIn(1f, 3.2f)
            fracX += dxDp / (5.5f * scale) * accel
            fracY += dyDp / (13f * scale) * accel
            val sx = truncLegacy(fracX)
            val sy = truncLegacy(fracY)
            fracX -= sx
            fracY -= sy
            gx += sx
            gy += sy
        }
        return MoveCount(gx, gy, 0)
    }

    private fun truncLegacy(value: Float): Int {
        val whole = value.toInt()
        return if (value < 0f && whole.toFloat() != value && abs(value) < 1f) 0 else whole
    }
}
