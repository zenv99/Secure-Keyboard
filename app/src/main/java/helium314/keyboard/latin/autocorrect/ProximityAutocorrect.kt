// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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
        /** Dictionary hit for these letters, or null. "don't" is stored under "dont" only when the source says so. */
        fun exact(letters: String): Pair<String, Int>? = null
    }

    class ListWords(private val items: List<VocabWord>) : WordSource {
        private val exactMap: Map<String, Pair<String, Int>> = buildMap {
            for (item in items) {
                val key = AutocorrectPolicy.lettersOf(item.word)
                if (key.isEmpty()) continue
                val prev = this[key]
                if (prev == null || item.probability > prev.second) this[key] = item.word to item.probability
            }
        }
        override val size: Int get() = items.size
        override fun word(index: Int) = items[index].word
        override fun probability(index: Int) = items[index].probability
        override fun exact(letters: String) = exactMap[letters]
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
        /** One touch point per typed letter, in the same units as [centreOf]. Null when the press position is unknown. */
        touches: Array<FloatArray?>? = null,
        centreOf: ((Char) -> FloatArray?)? = null,
    ): String? {
        if (typed.length !in 2..MAX_LENGTH) return null
        if (kept || AutocorrectPolicy.isLiteralToken(typed)) return null
        val typedLetters = AutocorrectPolicy.lettersOf(typed)
        if (typedLetters.length !in 2..MAX_LENGTH) return null
        if (isExactVocabularyWord(typed, words, distance)) return null

        contractions[typedLetters]?.let { form ->
            return applyCase(typed, form, sentenceStart)
        }
        // A general two-letter edit turned "yo" into "to". Only the one slip we know is safe.
        if (typedLetters.length == 2) {
            if (typedLetters == "iw") return applyCase(typed, "is", sentenceStart)
            return null
        }

        val limit = maxRawCost(typedLetters.length)
        if (limit <= 0f) return null
        val first = typedLetters[0]
        val indexes = words.indexesNear(first, distance)
        var bestScore = INF
        var bestWord: String? = null
        var bestRaw = INF
        var secondScore = INF
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
            val raw = alignmentCost(typedLetters, candidateLetters, distance, limit, touches, centreOf)
            if (raw > limit) continue
            if (raw > HEAVY_RAW && probability < HEAVY_MIN_PROBABILITY) continue
            val score = rank(raw, probability, overlap(typedLetters, candidateLetters), lenDiff)
            val sameAsBest = bestWord != null && AutocorrectPolicy.lettersOf(bestWord!!) == candidateLetters
            if (score < bestScore) {
                if (!sameAsBest) secondScore = bestScore
                bestScore = score
                bestRaw = raw
                bestWord = candidate
            } else if (!sameAsBest && score < secondScore) {
                secondScore = score
            }
        }
        val chosen = bestWord ?: return null
        // Nearly identical cheap candidates are ambiguous. A clearly better score is kept.
        if (bestRaw < 1.2f && secondScore - bestScore < 0.015f && secondScore < INF / 2) return null
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
        // One adjacent slip on a two-letter word ("iw" → "is"). A far substitution still costs ~2.4.
        length == 2 -> 0f
        length == 3 -> 1.00f
        length == 4 -> 1.30f
        length <= 6 -> 2.15f
        else -> 3.50f
    }

    /**
     * The space bar landed where a nearby letter belonged: "gobi g" → "going",
     * "havi g" → "having". [fragment] is the short token typed after the space.
     * Returns null when both pieces are already real words, so "the cat" stays.
     */
    fun spaceSlip(
        previous: String,
        fragment: String,
        words: WordSource,
        distance: (Char, Char) -> Float = KeyGeometry::qwertyDistance,
    ): String? {
        val left = AutocorrectPolicy.lettersOf(previous)
        val right = AutocorrectPolicy.lettersOf(fragment)
        if (left.length !in 2..16 || right.length !in 1..4) return null
        if (AutocorrectPolicy.isLiteralToken(previous) || AutocorrectPolicy.isLiteralToken(fragment)) return null
        val leftKnown = isExactVocabularyWord(previous, words, distance)
        val rightKnown = right.length >= 2 && isExactVocabularyWord(fragment, words, distance)
        if (leftKnown && rightKnown) return null
        var best: String? = null
        var bestRaw = INF
        // Only letters that sit beside the space bar. A wider set was joining real words.
        for (extra in charArrayOf('c', 'n', 'b', 'v', 'm')) {
            val glued = left + extra + right
            if (glued.length !in 2..MAX_LENGTH) continue
            val exact = knownWord(glued, words, distance)
            val candidate: String
            val raw: Float
            if (exact != null) {
                candidate = exact
                raw = 0f
            } else if (leftKnown) {
                continue
            } else {
                val fixed = correct(glued, words, distance) ?: continue
                val fixedLetters = AutocorrectPolicy.lettersOf(fixed)
                raw = alignmentCost(glued, fixedLetters, distance, 1.25f)
                if (raw > 1.20f) continue
                candidate = fixed
            }
            if (candidate.last().lowercaseChar() != right.last()) continue
            if (AutocorrectPolicy.lettersOf(candidate).length < left.length) continue
            if (raw < bestRaw) {
                bestRaw = raw
                best = candidate
            }
        }
        return best?.let { applyCase(previous, it, false) }
    }

    /**
     * Missed spaces, including a space bar that landed on c, v, b, n, or m.
     * "iamgoing" → "I am going", "whatbtime" → "what time".
     * A token that is itself a dictionary word or name is not split.
     */
    fun missingSpace(
        typed: String,
        words: WordSource,
        distance: (Char, Char) -> Float = KeyGeometry::qwertyDistance,
    ): String? {
        val letters = AutocorrectPolicy.lettersOf(typed)
        if (letters.length !in 4..24) return null
        if (AutocorrectPolicy.isLiteralToken(typed)) return null
        if (isExactVocabularyWord(typed, words, distance)) return null
        var best = bestSplit(letters, words)
        if (letters.length >= 6) {
            for (i in 1 until letters.lastIndex) {
                val ch = letters[i]
                if (ch != 'c' && ch != 'v' && ch != 'b' && ch != 'n' && ch != 'm') continue
                val cut = letters.removeRange(i, i + 1)
                if (isExactVocabularyWord(cut, words, distance)) continue
                val split = bestSplit(cut, words) ?: continue
                if (best == null || split.second > best.second) best = split
            }
        }
        return best?.first
    }

    /** Common pairs. The number is added to the sum of the two word probabilities. */
    private val bigrams = hashMapOf(
        "i am" to 80, "am going" to 80, "see you" to 80, "you tomorrow" to 70,
        "thank you" to 80, "on my" to 70, "my way" to 70, "what time" to 80,
        "gonna go" to 80, "is going" to 80, "you are" to 40, "let me" to 40,
        "be right" to 30, "right back" to 40, "on the" to 30, "in the" to 30,
        "to the" to 30, "for you" to 30, "with me" to 30, "call me" to 40,
        "tell me" to 40, "meet you" to 30, "need to" to 20, "want to" to 20,
        "have to" to 20, "going to" to 40, "to go" to 30, "at home" to 30,
    )

    private fun bestSplit(letters: String, words: WordSource): Pair<String, Int>? {
        val n = letters.length
        if (n < 4) return null
        val score = IntArray(n + 1) { Int.MIN_VALUE }
        val prev = IntArray(n + 1) { -1 }
        val count = IntArray(n + 1)
        val piece = arrayOfNulls<String>(n + 1)
        score[0] = 0
        for (i in 0 until n) {
            if (i != 0 && prev[i] < 0) continue
            val leftWord = if (i == 0) null else piece[i]
            val end = min(n, i + 12)
            for (j in i + 1..end) {
                val slice = letters.substring(i, j)
                if (slice.length == 1 && slice != "i" && slice != "a") continue
                val entry = when (slice) {
                    "i" -> "I" to 210
                    "a" -> "a" to 180
                    else -> words.exact(slice) ?: continue
                }
                if (slice != "i" && slice != "a" && entry.second < 40) continue
                val wordsSoFar = if (i == 0) 1 else count[i] + 1
                if (wordsSoFar > 4) continue
                val bonus = if (leftWord == null) 0 else bigram(leftWord, entry.first)
                val next = score[i] + entry.second + bonus
                if (next > score[j]) {
                    score[j] = next
                    prev[j] = i
                    count[j] = wordsSoFar
                    piece[j] = entry.first
                }
            }
        }
        if (prev[n] < 0 || count[n] < 2) return null
        if (score[n] / count[n] < 70) return null
        val parts = ArrayList<String>(count[n])
        var k = n
        while (k > 0) {
            parts.add(piece[k]!!)
            k = prev[k]
        }
        parts.reverse()
        return parts.joinToString(" ") to score[n]
    }

    private fun bigram(left: String, right: String): Int {
        val key = left.lowercase(Locale.ROOT) + " " + right.lowercase(Locale.ROOT)
        return bigrams[key] ?: 12
    }

    private fun knownWord(typed: String, words: WordSource, distance: (Char, Char) -> Float): String? =
        knownEntry(typed, words, distance)?.first

    private fun knownEntry(typed: String, words: WordSource, distance: (Char, Char) -> Float): Pair<String, Int>? {
        val letters = AutocorrectPolicy.lettersOf(typed)
        if (letters.isEmpty()) return null
        val indexes = words.indexesNear(letters[0], distance)
        var best: Pair<String, Int>? = null
        for (index in indexes) {
            val word = words.word(index)
            val same = word.equals(letters, ignoreCase = true)
                    || (word.none { it == '\'' || it == '’' }
                    && AutocorrectPolicy.lettersOf(word) == letters
                    && word.length == letters.length)
            if (!same) continue
            val probability = words.probability(index)
            if (best == null || probability > best.second) best = word to probability
        }
        return best
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
    fun alignmentCost(
        typed: String,
        target: String,
        distance: (Char, Char) -> Float,
        limit: Float,
        touches: Array<FloatArray?>? = null,
        centreOf: ((Char) -> FloatArray?)? = null,
    ): Float {
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
                val sub = if (typed[i - 1] == target[j - 1]) 0f
                    else substitutionCost(typed[i - 1], target[j - 1], distance, touches?.getOrNull(i - 1), centreOf)
                var best = min(prev[j - 1] + sub, prev[j] + indelCost(typed[i - 1], target[j - 1], distance))
                best = min(best, curr[j - 1] + indelCost(target[j - 1], typed[i - 1], distance))
                if (i > 1 && j > 1 && typed[i - 1] == target[j - 2] && typed[i - 2] == target[j - 1]) {
                    val swap = if (distance(typed[i - 1], typed[i - 2]) <= KeyGeometry.ADJACENT) 0.62f else 0.90f
                    best = min(best, before[j - 2] + swap)
                }
                curr[j] = best
                if (best < rowBest) rowBest = best
            }
            // A shifted typo is expensive in the middle and cheap at the end ("xoredrt" → "correct").
            // Abandoning the row as soon as it exceeds the limit drops those matches.
            val oldest = before
            before = prev
            prev = curr
            curr = oldest
        }
        return prev[m]
    }

    private fun substitutionCost(
        a: Char,
        b: Char,
        distance: (Char, Char) -> Float,
        touch: FloatArray?,
        centreOf: ((Char) -> FloatArray?)?,
    ): Float {
        // A press recorded closer to another key than to the key that was emitted
        // is charged by that distance, not by the two key centres.
        val d = if (touch != null && centreOf != null) {
            val target = centreOf(b)
            if (target == null) distance(a, b)
            else hypot(touch[0] - target[0], touch[1] - target[1])
        } else distance(a, b)
        return if (d <= KeyGeometry.ADJACENT) 0.42f + 0.28f * d else 2.55f
    }

    private fun indelCost(ch: Char, neighbor: Char?, distance: (Char, Char) -> Float): Float {
        if (neighbor == null) return 0.95f
        return if (distance(ch, neighbor) <= KeyGeometry.ADJACENT) 0.78f else 1.15f
    }
}

