// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlin.math.max

/**
 * Learns how far, on average, a person's taps land from the centre of the key they hit.
 * Offsets are fractions of the key size, so the result does not depend on screen size.
 * Pure logic with no Android dependencies, so it can be unit tested.
 */
class TouchOffsetLearner(var meanX: Float = 0f, var meanY: Float = 0f, var samples: Int = 0) {

    fun add(fractionX: Float, fractionY: Float) {
        val fx = fractionX.coerceIn(-0.5f, 0.5f)
        val fy = fractionY.coerceIn(-0.5f, 0.5f)
        if (samples < Int.MAX_VALUE) samples++
        // plain running mean at first, then a slow moving average so it keeps adapting
        val weight = max(1f / samples, SMOOTHING)
        meanX += weight * (fx - meanX)
        meanY += weight * (fy - meanY)
    }

    /** Horizontal shift (fraction of key width) to move a touch back toward the key centre. */
    fun correctionX(): Float = correction(meanX)

    /** Vertical shift (fraction of key height) to move a touch back toward the key centre. */
    fun correctionY(): Float = correction(meanY)

    fun reset() {
        meanX = 0f
        meanY = 0f
        samples = 0
    }

    private fun correction(mean: Float): Float =
        if (samples < MIN_SAMPLES) 0f else (-mean).coerceIn(-MAX_CORRECTION, MAX_CORRECTION)

    companion object {
        /** No correction is applied until this many taps have been seen. */
        const val MIN_SAMPLES = 300
        /** Never shift a touch by more than this fraction of the key size. */
        const val MAX_CORRECTION = 0.12f
        const val SMOOTHING = 0.02f
    }
}
