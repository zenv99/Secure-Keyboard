// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Turns finger travel on the space bar into fractional character and line steps.
 *
 * Slow movement is about one grapheme per 5.5 dp and one line per 13 dp (sensitivity 50).
 * Faster movement accelerates, up to 3.2x. Samples offered before the next frame are summed,
 * so a 60 Hz or 120 Hz stream emits at most one cursor update per frame.
 */
class TrackpadMotion {
    private var density = 1f
    private var sensitivity = 50
    private var fracX = 0f
    private var fracY = 0f
    private var lastEventNs = 0L
    private var pending = false
    private var firstEventNs = 0L
    private val latenciesMs = ArrayList<Float>(64)

    fun configure(density: Float, sensitivity: Int) {
        this.density = density.coerceAtLeast(0.5f)
        this.sensitivity = sensitivity.coerceIn(0, 100)
    }

    fun reset() {
        fracX = 0f
        fracY = 0f
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
        val speed = hypot(dxDp, dyDp) / (dtNs / 1_000_000_000f)
        val accel = (1f + (speed - 60f).coerceAtLeast(0f) / 500f).coerceIn(1f, 3.2f)
        val scale = 1.45f - sensitivity * 0.009f
        fracX += dxDp / (5.5f * scale) * accel
        fracY += dyDp / (13f * scale) * accel
        if (!pending) firstEventNs = eventNs
        pending = true
    }

    /** Whole steps ready for this frame, plus the wait from the first queued sample. */
    fun consume(applyNs: Long): TrackpadSteps {
        if (!pending) return TrackpadSteps(0, 0, 0f)
        val latency = (applyNs - firstEventNs) / 1_000_000f
        if (latenciesMs.size == 64) latenciesMs.removeAt(0)
        latenciesMs.add(latency)
        val sx = truncTowardZero(fracX)
        val sy = truncTowardZero(fracY)
        fracX -= sx
        fracY -= sy
        pending = false
        return TrackpadSteps(sx, sy, latency)
    }

    fun latencyMillis(): List<Float> = latenciesMs.toList()

    private fun truncTowardZero(value: Float): Int {
        val whole = value.toInt()
        return if (value < 0f && whole.toFloat() != value && abs(value) < 1f) 0 else whole
    }
}

data class TrackpadSteps(val graphemes: Int, val lines: Int, val latencyMs: Float)

/**
 * A window of text around the caret. Horizontal steps walk graphemes and can cross line breaks.
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
            if (graphemes[i] == "\n") lines.add(i + 1)
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
        for (i in charEnds.indices) {
            if (charEnds[i] >= charIndex) return i + if (charEnds[i] == charIndex) 1 else 0
        }
        return graphemes.size
    }

    private fun lineOf(index: Int): Int {
        var line = 0
        for (i in lineStarts.indices) if (lineStarts[i] <= index) line = i else break
        return line
    }

    private fun columnOf(index: Int): Int = index - lineStarts[lineOf(index)]
}

/** p50/p95 of a list of millisecond samples. */
fun percentile(values: List<Float>, p: Float): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val rank = ((sorted.size - 1) * p).roundToLong().toInt().coerceIn(0, sorted.lastIndex)
    return sorted[rank]
}
