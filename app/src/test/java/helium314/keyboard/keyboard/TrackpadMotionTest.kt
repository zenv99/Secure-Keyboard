// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Motion numbers below are measured by running this test, not guessed.
 * Density 2.75 is a 440 dpi phone. Sensitivity 50 is the default (1x scale).
 */
class TrackpadMotionTest {
    @Test fun slowDragNeverJumpsMoreThanOneCharacterPerFrame() {
        val slow60 = stream(dxPxPerFrame = 2f, frames = 120, frameNs = 16_666_667L)
        val slow120 = stream(dxPxPerFrame = 1f, frames = 240, frameNs = 8_333_333L)
        assertTrue(slow60.maxGraphemes <= 1, "60 Hz max step ${slow60.maxGraphemes}")
        assertTrue(slow120.maxGraphemes <= 1, "120 Hz max step ${slow120.maxGraphemes}")
        assertTrue(slow60.totalGraphemes > 0)
        assertTrue(slow120.totalGraphemes > 0)
        val ratio = slow120.totalGraphemes.toFloat() / slow60.totalGraphemes
        assertTrue(ratio in 0.7f..1.4f, "60 Hz ${slow60.totalGraphemes} vs 120 Hz ${slow120.totalGraphemes}")
    }

    @Test fun fastFlickMovesFurtherThanTheSameDistanceDoneSlowly() {
        val slow = stream(dxPxPerFrame = 2f, frames = 80, frameNs = 16_666_667L)
        val fast = stream(dxPxPerFrame = 16f, frames = 10, frameNs = 8_333_333L)
        assertEquals(160, slow.frames * 2)
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

    @Test fun cursorCrossesLinesAndKeepsItsColumn() {
        val doc = TrackpadDocument("hello", "\nworld\n!")
        assertEquals(5, doc.charOffset())
        assertEquals(1, doc.move(1, 0))
        assertEquals(6, doc.charOffset())
        val down = doc.move(0, 1)
        assertTrue(down >= 1)
        val text = "hello\nworld\n!"
        assertEquals('!', text[doc.charOffset()])

        val column = TrackpadDocument("a", "\ncd")
        column.move(0, 1)
        assertEquals(3, column.charOffset())
        assertEquals('d', "a\ncd"[column.charOffset()])
    }

    private fun motion(): TrackpadMotion = TrackpadMotion().apply { configure(2.75f, 50) }

    private data class StreamResult(val totalGraphemes: Int, val maxGraphemes: Int, val frames: Int)

    private fun stream(dxPxPerFrame: Float, frames: Int, frameNs: Long): StreamResult {
        val motion = motion()
        var total = 0
        var maxStep = 0
        var t = 0L
        repeat(frames) {
            motion.offer(dxPxPerFrame, 0f, t)
            val steps = motion.consume(t + frameNs)
            total += steps.graphemes
            if (abs(steps.graphemes) > maxStep) maxStep = abs(steps.graphemes)
            t += frameNs
        }
        return StreamResult(total, maxStep, frames)
    }
}
