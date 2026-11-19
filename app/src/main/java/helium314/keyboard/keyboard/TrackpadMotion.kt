// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Turns finger travel into fractional character and line steps, the way the iOS space bar does.
 *
 * Touch deltas go through an exponential low-pass, so finger jitter cancels instead of stepping
 * the caret. The first few dp are a dead zone. After that, slow movement needs about 10 dp per
 * character and a clearly larger distance per line. Acceleration starts only once the finger is
 * actually moving quickly, and it is capped, as is the number of characters emitted in one frame.
 */
class TrackpadMotion {
    private var density = 1f
    private var sensitivity = 50
    private var smoothX = 0f
    private var smoothY = 0f
    private var accX = 0f
    private var accY = 0f
    private var deadX = 0f
    private var deadY = 0f
    private var deadArmed = true
    private var lastEventNs = 0L
    private var pending = false
    private var firstEventNs = 0L
    private val latenciesMs = ArrayList<Float>(64)

    fun configure(density: Float, sensitivity: Int) {
        this.density = density.coerceAtLeast(0.5f)
        this.sensitivity = sensitivity.coerceIn(0, 100)
    }

    fun reset() {
        smoothX = 0f
        smoothY = 0f
        accX = 0f
        accY = 0f
        deadX = 0f
        deadY = 0f
        deadArmed = true
        lastEventNs = 0L
        pending = false
        firstEventNs = 0L
    }

    /** Queue one finger sample. [eventNs] must use the same clock as [consume]'s apply time. */
    fun offer(dxPx: Float, dyPx: Float, eventNs: Long) {
        if (dxPx == 0f && dyPx == 0f) return
        val dtNs = if (lastEventNs == 0L) 8_000_000L else (eventNs - lastEventNs).coerceIn(1_000_000L, 50_000_000L)
        lastEventNs = eventNs
        val dxDp = dxPx / density
        val dyDp = dyPx / density
        // Low-pass. A jittery sample is mostly the previous direction, so it cannot walk the caret.
        smoothX = SMOOTH_ALPHA * dxDp + (1f - SMOOTH_ALPHA) * smoothX
        smoothY = SMOOTH_ALPHA * dyDp + (1f - SMOOTH_ALPHA) * smoothY
        var ux = smoothX
        var uy = smoothY
        if (deadArmed) {
            deadX += ux
            deadY += uy
            if (hypot(deadX, deadY) < DEAD_ZONE_DP) {
                if (!pending) firstEventNs = eventNs
                pending = true
                return
            }
            deadArmed = false
            val travelled = hypot(deadX, deadY).coerceAtLeast(0.001f)
            val overflow = travelled - DEAD_ZONE_DP
            ux = deadX / travelled * overflow
            uy = deadY / travelled * overflow
            smoothX = 0f
            smoothY = 0f
        }
        val speed = hypot(ux, uy) / (dtNs / 1_000_000_000f)
        val accel = (1f + (speed - ACCEL_START_DP_PER_S).coerceAtLeast(0f) / ACCEL_RANGE_DP_PER_S)
            .coerceIn(1f, ACCEL_CAP)
        val scale = 1.45f - sensitivity * 0.009f
        accX += ux / (CHAR_DP * scale) * accel
        accY += uy / (LINE_DP * scale) * accel
        if (!pending) firstEventNs = eventNs
        pending = true
    }

    /** Whole steps ready for this frame, plus the wait from the first queued sample. */
    fun consume(applyNs: Long): TrackpadSteps {
        if (!pending) return TrackpadSteps(0, 0, 0f)
        val latency = (applyNs - firstEventNs) / 1_000_000f
        if (latenciesMs.size == 64) latenciesMs.removeAt(0)
        latenciesMs.add(latency)
        val sx = truncTowardZero(accX).coerceIn(-STEP_CAP, STEP_CAP)
        val sy = truncTowardZero(accY).coerceIn(-STEP_CAP, STEP_CAP)
        accX -= sx
        accY -= sy
        pending = false
        return TrackpadSteps(sx, sy, latency)
    }

    fun latencyMillis(): List<Float> = latenciesMs.toList()

    private fun truncTowardZero(value: Float): Int {
        val whole = value.toInt()
        return if (value < 0f && whole.toFloat() != value && abs(value) < 1f) 0 else whole
    }

    private companion object {
        /** Exponential smoothing of each touch delta. Lower keeps more of the previous sample. */
        const val SMOOTH_ALPHA = 0.62f
        /** Finger travel ignored at the start of a drag, so settling on the space bar does not jump. */
        const val DEAD_ZONE_DP = 1.5f
        /** About one character per 10 dp at a slow speed (the 8–12 dp iOS range). */
        const val CHAR_DP = 10f
        /** Lines need a clearly larger movement than characters. */
        const val LINE_DP = 28f
        /** Acceleration stays at 1x until the finger is moving briskly, then rises gently. */
        const val ACCEL_START_DP_PER_S = 180f
        const val ACCEL_RANGE_DP_PER_S = 260f
        const val ACCEL_CAP = 2.6f
        /** A single frame never throws the caret across the field. */
        const val STEP_CAP = 8
    }
}

data class TrackpadSteps(val graphemes: Int, val lines: Int, val latencyMs: Float)

/**
 * A window of text around the caret. Horizontal steps walk graphemes and cross line breaks.
 * Vertical steps keep the column the finger was aiming at.
 */
class TrackpadDocument(before: String, after: String) {
    private val graphemes: List<String>
    private val charEnds: IntArray
    private val lineStarts: IntArray
    var graphemeIndex: Int
        private set
    private var preferredColumn: Int

    init {
        val full = before + after
        val parts = ArrayList<String>()
        val ends = ArrayList<Int>()
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(full)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            parts.add(full.substring(start, end))
            ends.add(end)
            start = end
            end = iterator.next()
        }
        graphemes = parts
        charEnds = ends.toIntArray()
        val lines = ArrayList<Int>()
        lines.add(0)
        for (i in graphemes.indices) {
            if (graphemes[i] == "\n" && i + 1 <= graphemes.size) lines.add(i + 1)
        }
        lineStarts = lines.toIntArray()
        graphemeIndex = graphemeIndexAtChar(before.length)
        preferredColumn = columnOf(graphemeIndex)
    }

    /** @return how many graphemes the caret actually moved. */
    fun move(graphemesDelta: Int, linesDelta: Int): Int {
        val before = graphemeIndex
        if (graphemesDelta != 0) {
            graphemeIndex = (graphemeIndex + graphemesDelta).coerceIn(0, graphemes.size)
            preferredColumn = columnOf(graphemeIndex)
        }
        if (linesDelta != 0) {
            val line = lineOf(graphemeIndex)
            val target = (line + linesDelta).coerceIn(0, lineStarts.size - 1)
            val lineStart = lineStarts[target]
            val lineEndExclusive = if (target + 1 < lineStarts.size) lineStarts[target + 1] else graphemes.size
            val contentEnd = if (lineEndExclusive > lineStart && graphemes[lineEndExclusive - 1] == "\n")
                lineEndExclusive - 1 else lineEndExclusive
            graphemeIndex = (lineStart + preferredColumn).coerceIn(lineStart, contentEnd)
        }
        return abs(graphemeIndex - before)
    }

    fun charOffset(): Int = if (graphemeIndex <= 0) 0 else charEnds[graphemeIndex - 1]

    private fun graphemeIndexAtChar(charIndex: Int): Int {
        if (charIndex <= 0) return 0
        for (i in charEnds.indices) if (charEnds[i] >= charIndex) return i + if (charEnds[i] == charIndex) 1 else 0
        return graphemes.size
    }

    private fun lineOf(index: Int): Int {
        var line = 0
        for (i in lineStarts.indices) if (lineStarts[i] <= index) line = i else break
        return line
    }

    private fun columnOf(index: Int): Int = index - lineStarts[lineOf(index)]
}

/** p50/p95 of a list of millisecond samples. Measured, not estimated. */
fun percentile(values: List<Float>, p: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val rank = ((sorted.size - 1) * p).roundToLong().toInt().coerceIn(0, sorted.lastIndex)
    return sorted[rank]
}
