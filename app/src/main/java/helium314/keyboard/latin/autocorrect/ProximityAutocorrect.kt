// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * On-device autocorrect aimed at an iOS-like balance.
 *
 * Typos are scored with a proximity-weighted edit distance: an adjacent-key slip is cheap,
 * a far substitution is not, and one extra or missing letter is allowed. "xoredrt" aligns to
 * "correct" that way (x for c, a shifted r, d for c, one extra r) even though a same-length
 * comparison makes the tail look like a far miss.
 *
 * A word that is already spelled correctly is not replaced. Contractions such as dont → don't
 * and im → I'm are applied only when the typed form is not itself a dictionary word.
 */
object ProximityAutocorrect {
    const val MIN_PROBABILITY = 40
    private const val HEAVY_RAW = 2.2f
    private const val HEAVY_MIN_PROBABILITY = 70
    private const val MAX_LENGTH = 24
    private const val INF = 1e6f

    data class VocabWord(val word: String, val probability: Int)

    interface WordSource {
        val size: Int
        fun word(index: Int): String
        fun probability(index: Int): Int
        fun indexesNear(first: Char, distance: (Char, Char) -> Float): IntArray
    }

    class ListWords(private val items: List<VocabWord>) : WordSource {
        override val size: Int get() = items.size
        override fun word(index: Int) = items[index].word
        override fun probability(index: Int) = items[index].probability
        override fun indexesNear(first: Char, distance: (Char, Char) -> Float): IntArray {
            val out = ArrayList<Int>()
            val f = KeyGeometry.baseLetter(first)
            for (i in items.indices) {
                val w = items[i].word
                if (w.isEmpty()) continue
                val c = KeyGeometry.baseLetter(w[0])
                if (distance(f, c) <= 2.3f) out.add(i)
            }
            return out.toIntArray()
        }
    }

    /**
     * Unambiguous contractions. Forms that are also ordinary words (ill, its, lets, id)
     * are intentionally absent so a valid word is not rewritten.
     */
    private val contractions = mapOf(
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "couldnt" to "couldn't",
        "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "youre" to "you're",
        "theyre" to "they're",
        "youve" to "you've",
        "theyve" to "they've",
        "weve" to "we've",
        "ive" to "I've",
        "im" to "I'm",
    )

    fun correct(
        typed: String,
        words: WordSource,
        distance: (Char, Char) -> Float = KeyGeometry::qwertyDistance,
        kept: Boolean = false,
        sentenceStart: Boolean = false,
    ): String? {
        if (typed.length !in 2..MAX_LENGTH) return null
        if (kept || AutocorrectPolicy.isLiteralToken(typed)) return null
        val typedLetters = AutocorrectPolicy.lettersOf(typed)
        if (typedLetters.length !in 2..MAX_LENGTH) return null
        if (isExactVocabularyWord(typed, words, distance)) return null

        contractions[typedLetters]?.let { form ->
            return applyCase(typed, form, sentenceStart)
        }

        val limit = maxRawCost(typedLetters.length)
        if (limit <= 0f) return null
        val first = typedLetters[0]
        val indexes = words.indexesNear(first, distance)
        var bestScore = INF
        var secondScore = INF
        var bestWord: String? = null
        var bestRaw = INF
        for (index in indexes) {
            val probability = words.probability(index)
            if (probability < MIN_PROBABILITY) continue
            val candidate = words.word(index)
            val candidateLetters = AutocorrectPolicy.lettersOf(candidate)
            if (candidateLetters.isEmpty()) continue
            if (candidateLetters == typedLetters) continue
            val lenDiff = abs(candidateLetters.length - typedLetters.length)
            if (lenDiff > 2) continue
            // "xored" is a prefix of "xoredrt" with two leftovers. That is not one slip.
            if (lenDiff >= 2 && typedLetters.startsWith(candidateLetters)) continue
            val raw = alignmentCost(typedLetters, candidateLetters, distance, limit)
            if (raw > limit) continue
            if (raw > HEAVY_RAW && probability < HEAVY_MIN_PROBABILITY) continue
            val score = rank(raw, probability, overlap(typedLetters, candidateLetters), lenDiff)
            if (score < bestScore) {
                secondScore = bestScore
                bestScore = score
                bestRaw = raw
                bestWord = candidate
            } else if (score < secondScore) {
                secondScore = score
            }
        }
        val chosen = bestWord ?: return null
        if (secondScore - bestScore < 0.09f && secondScore < INF / 2) return null
        if (bestRaw > limit) return null
        return applyCase(typed, chosen, sentenceStart)
    }

    /** True when [candidate] is a believable fat-finger or one-edit fix of an unknown [typed] word. */
    fun isPlausibleCorrection(typed: String, candidate: String): Boolean {
        if (AutocorrectPolicy.isCaseApostropheOrAccentOnly(typed, candidate)) return true
        val a = AutocorrectPolicy.lettersOf(typed)
        val b = AutocorrectPolicy.lettersOf(candidate)
        if (a.isEmpty() || b.isEmpty() || abs(a.length - b.length) > 2) return false
        val raw = alignmentCost(a, b, KeyGeometry::qwertyDistance, maxRawCost(a.length) + 0.01f)
        return raw <= maxRawCost(a.length)
    }

    fun maxRawCost(length: Int): Float = when {
        length <= 1 -> 0f
        length == 2 -> 0.35f
        length == 3 -> 1.05f
        length == 4 -> 1.70f
        length <= 6 -> 2.40f
        else -> 3.50f
    }

    private fun isExactVocabularyWord(typed: String, words: WordSource, distance: (Char, Char) -> Float): Boolean {
        val folded = typed.lowercase(Locale.ROOT)
        val letters = AutocorrectPolicy.lettersOf(typed)
        if (letters.isEmpty()) return false
        val indexes = words.indexesNear(letters[0], distance)
        for (index in indexes) {
            val word = words.word(index)
            if (word.equals(folded, ignoreCase = true)) return true
            // Same letters and same apostrophes: "don't" is known, "dont" is not.
            if (word.none { it == '\'' || it == '’' } && AutocorrectPolicy.lettersOf(word) == letters && word.length == letters.length) {
                return true
            }
        }
        return false
    }

    private fun rank(raw: Float, probability: Int, sharedLetters: Int, lenDiff: Int): Float {
        val freq = (1f - probability.coerceIn(0, 255) / 255f) * 0.15f
        return raw - 0.22f * sharedLetters + freq + 0.45f * lenDiff
    }

    private fun overlap(a: String, b: String): Int {
        val counts = IntArray(26)
        for (ch in a) {
            if (ch in 'a'..'z') counts[ch - 'a']++
        }
        var n = 0
        for (ch in b) {
            if (ch in 'a'..'z' && counts[ch - 'a'] > 0) {
                counts[ch - 'a']--
                n++
            }
        }
        return n
    }

    fun applyCase(typed: String, corrected: String, sentenceStart: Boolean): String {
        val letters = typed.filter { it.isLetter() }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) {
            return corrected.uppercase(Locale.ROOT)
        }
        val capitalize = sentenceStart || typed.firstOrNull()?.isUpperCase() == true
        if (!capitalize) return corrected
        return corrected.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
        }
    }

    /**
     * Weighted Damerau–Levenshtein.
     * Adjacent substitution is cheap. A far substitution costs more than inserting and deleting,
     * so a shifted typo ("xoredrt" / "correct") is not forced into a bad column-by-column match.
     * Returns a value greater than [limit] when every alignment exceeds it.
     */
    fun alignmentCost(typed: String, target: String, distance: (Char, Char) -> Float, limit: Float): Float {
        val n = typed.length
        val m = target.length
        if (n == 0 || m == 0) return INF
        // row i-2, row i-1, row i
        var before = FloatArray(m + 1) { INF }
        var prev = FloatArray(m + 1) { INF }
        var curr = FloatArray(m + 1) { INF }
        prev[0] = 0f
        for (j in 1..m) prev[j] = prev[j - 1] + indelCost(target[j - 1], null, distance)
        for (i in 1..n) {
            curr[0] = prev[0] + indelCost(typed[i - 1], null, distance)
            var rowBest = curr[0]
            for (j in 1..m) {
                val sub = if (typed[i - 1] == target[j - 1]) 0f else substitutionCost(typed[i - 1], target[j - 1], distance)
                var best = min(prev[j - 1] + sub, prev[j] + indelCost(typed[i - 1], target[j - 1], distance))
                best = min(best, curr[j - 1] + indelCost(target[j - 1], typed[i - 1], distance))
                if (i > 1 && j > 1 && typed[i - 1] == target[j - 2] && typed[i - 2] == target[j - 1]) {
                    val swap = if (distance(typed[i - 1], typed[i - 2]) <= KeyGeometry.ADJACENT) 0.62f else 0.90f
                    best = min(best, before[j - 2] + swap)
                }
                curr[j] = best
                if (best < rowBest) rowBest = best
            }
            // Do not abandon the row early. A transposition is charged on the next row
            // against the row before this one, so an expensive middle row can still
            // finish as a cheap swap ("teh" → "the").
            if (rowBest > limit && prev.min() > limit && before.min() > limit) return limit + 1f
            val oldest = before
            before = prev
            prev = curr
            curr = oldest
        }
        return prev[m]
    }

    private fun substitutionCost(a: Char, b: Char, distance: (Char, Char) -> Float): Float {
        val d = distance(a, b)
        return if (d <= KeyGeometry.ADJACENT) 0.42f + 0.28f * d else 2.4f
    }

    private fun indelCost(ch: Char, neighbor: Char?, distance: (Char, Char) -> Float): Float {
        if (neighbor == null) return 0.95f
        return if (distance(ch, neighbor) <= KeyGeometry.ADJACENT) 0.78f else 1.15f
    }
}
