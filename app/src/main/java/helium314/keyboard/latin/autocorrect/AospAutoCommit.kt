// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Whether a candidate may replace what was typed.
 *
 * The score is the one in AOSP LatinIME
 * `AutocorrectionThresholdUtils::calcNormalizedScore`: Damerau–Levenshtein distance,
 * then `score / SUGGEST_INTERFACE_OUTPUT_SCALE * (1 - distance / length)`.
 * The bar is HeliBoard's autocorrect-confidence setting
 * (`0.58 - 0.5 * confidence^0.33` in SettingsValues). Default confidence is 0.42.
 *
 * A capital in the middle of a sentence has to clear a higher bar. That is the
 * same idea as AOSP refusing to "fix" a word the user already capitalized.
 */
object AospAutoCommit {
    /** AOSP `SUGGEST_INTERFACE_OUTPUT_SCALE` in defines.h. */
    const val OUTPUT_SCALE = 1_000_000f

    /** HeliBoard default, [helium314.keyboard.latin.settings.Defaults.PREF_AUTO_CORRECT_CONFIDENCE]. */
    const val DEFAULT_CONFIDENCE = 0.42f

    fun threshold(confidence: Float = DEFAULT_CONFIDENCE): Float {
        return 0.58f - 0.5f * confidence.toDouble().pow(0.33).toFloat()
    }

    /**
     * Dictionary probability (0..255) and the proximity cost of the alignment,
     * scaled the way AOSP scales a spatial × language score.
     */
    fun scaledScore(probability: Int, proximityRaw: Float): Int {
        // The alignment already dropped a far key. This is the unigram part AOSP
        // divides by SUGGEST_INTERFACE_OUTPUT_SCALE. Edit distance is applied in normalized().
        val language = (probability / 255f).coerceIn(0.22f, 1f)
        val kept = (1f - proximityRaw / 40f).coerceIn(0.9f, 1f)
        return (language * kept * OUTPUT_SCALE).toInt().coerceAtLeast(1)
    }

    fun normalized(before: String, after: String, score: Int): Float {
        if (before.isEmpty() || after.isEmpty()) return 0f
        val distance = damerau(before, after)
        var spaces = 0
        for (ch in after) if (ch == ' ') spaces++
        if (spaces == after.length) return 0f
        if (score <= 0 || distance >= after.length) return 0f
        val weight = 1f - distance.toFloat() / after.length.toFloat()
        return (score / OUTPUT_SCALE) * weight
    }

    fun commits(
        typed: String,
        candidate: String,
        probability: Int,
        proximityRaw: Float,
        sentenceStart: Boolean,
    ): Boolean {
        if (typed.equals(candidate, ignoreCase = true)) return false
        val typedLetters = AutocorrectPolicy.lettersOf(typed)
        val candidateLetters = AutocorrectPolicy.lettersOf(candidate)
        if (typedLetters.isEmpty() || candidateLetters.isEmpty()) return false
        val lenDiff = abs(typedLetters.length - candidateLetters.length)
        // Two added or dropped letters ("webull" → "well") are not one slip.
        if (lenDiff >= 2) return false
        val distance = damerau(typedLetters, candidateLetters)
        // A shorter or longer word that still differs by a second edit ("shopee" → "shoes").
        if (lenDiff == 1 && distance >= 2) return false
        val score = scaledScore(probability, proximityRaw)
        val norm = normalized(typedLetters, candidateLetters, score)
        val bar = threshold()
        val midSentenceName = !sentenceStart
            && typed.firstOrNull()?.isUpperCase() == true
            && typed.drop(1).none { it.isUpperCase() }
        // One adjacent slip or one transposition of a dictionary word. AOSP's spatial
        // score is high for that even when the unigram is only average.
        val oneSlip = proximityRaw <= 1.05f && distance <= 1 && probability >= 40
        if (oneSlip && !midSentenceName) return true
        val need = if (midSentenceName) (bar + 0.15f).coerceAtMost(0.90f) else bar
        return norm >= need
    }

    /** Unit-cost Damerau–Levenshtein, base letters, matching AOSP's edit-distance policy. */
    fun damerau(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (KeyGeometry.baseLetter(a[i - 1]) == KeyGeometry.baseLetter(b[j - 1])) 0 else 1
                var best = min(d[i - 1][j] + 1, d[i][j - 1] + 1)
                best = min(best, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1
                    && KeyGeometry.baseLetter(a[i - 1]) == KeyGeometry.baseLetter(b[j - 2])
                    && KeyGeometry.baseLetter(a[i - 2]) == KeyGeometry.baseLetter(b[j - 1])
                ) {
                    best = min(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d[n][m]
    }
}
