// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.TouchOffsetLearner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchOffsetLearnerTest {
    @Test fun noCorrectionBeforeEnoughSamples() {
        val l = TouchOffsetLearner()
        repeat(TouchOffsetLearner.MIN_SAMPLES - 1) { l.add(0.2f, 0.2f) }
        assertEquals(0f, l.correctionX())
        assertEquals(0f, l.correctionY())
    }

    @Test fun learnsConsistentOffsetAndShiftsBack() {
        val l = TouchOffsetLearner()
        repeat(400) { l.add(0.08f, -0.05f) } // user taps right of and above centre
        assertTrue(l.correctionX() < 0f, "should shift touches left")
        assertTrue(l.correctionY() > 0f, "should shift touches down")
        assertEquals(-0.08f, l.correctionX(), 0.005f)
        assertEquals(0.05f, l.correctionY(), 0.005f)
    }

    @Test fun correctionIsClamped() {
        val l = TouchOffsetLearner()
        repeat(500) { l.add(0.5f, -0.5f) }
        assertEquals(-TouchOffsetLearner.MAX_CORRECTION, l.correctionX(), 1e-6f)
        assertEquals(TouchOffsetLearner.MAX_CORRECTION, l.correctionY(), 1e-6f)
    }

    @Test fun centredTapsGiveNoCorrection() {
        val l = TouchOffsetLearner()
        repeat(500) { i -> l.add(if (i % 2 == 0) 0.1f else -0.1f, 0f) }
        assertEquals(0f, l.correctionX(), 0.01f)
        assertEquals(0f, l.correctionY(), 0.001f)
    }

    @Test fun outOfRangeInputIsClamped() {
        val l = TouchOffsetLearner()
        l.add(10f, -10f)
        assertEquals(0.5f, l.meanX, 1e-6f)
        assertEquals(-0.5f, l.meanY, 1e-6f)
    }

    @Test fun adaptsWhenHabitChanges() {
        val l = TouchOffsetLearner()
        repeat(400) { l.add(0.1f, 0f) }
        repeat(600) { l.add(-0.1f, 0f) }
        assertTrue(l.correctionX() > 0f, "should have followed the new habit")
    }

    @Test fun resetClearsEverything() {
        val l = TouchOffsetLearner()
        repeat(400) { l.add(0.1f, 0.1f) }
        l.reset()
        assertEquals(0, l.samples)
        assertEquals(0f, l.correctionX())
    }
}
