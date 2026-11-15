// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * Open-source swipe ("glide") typing, fully on device.
 *
 * The finger path is compared with the ideal path through the keys of every candidate word
 * (shape + position, in the spirit of SHARK2): candidates must start and end near the ends of the
 * swipe, and are ranked by how closely the sampled paths agree, with common words favoured.
 * The vocabulary comes from the keyboard's own dictionary. Nothing is stored, logged or sent anywhere.
 */
object GlideTyping {
    private const val SAMPLES = 40
    private const val MIN_PROBABILITY = 20
    private const val MAX_WORDS = 90_000
    private const val MAX_WORD_LENGTH = 20
    private const val MAX_RESULTS = 8

    private class Vocab(
        val words: Array<String>,
        val seqs: Array<IntArray>, // lower-case letters of the word without apostrophes
        val probs: IntArray,
        val byFirst: Map<Int, IntArray>,
    )

    private val vocabs: MutableMap<Dictionary, Vocab> = Collections.synchronizedMap(WeakHashMap())
    private val loading: MutableSet<Dictionary> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Dictionary, Boolean>()))

    /** Starts loading the vocabulary of [dict] in the background (once). */
    @JvmStatic
    fun prepare(dict: Dictionary) {
        if (vocabs.containsKey(dict) || !loading.add(dict)) return
        Thread({
            try {
                vocabs[dict] = buildVocab(dict)
            } catch (_: Throwable) {
                // no vocabulary: swipes simply produce no suggestions
            } finally {
                loading.remove(dict)
            }
        }, "glide-vocab").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun buildVocab(dict: Dictionary): Vocab {
        val best = HashMap<String, Int>()
        dict.forEachWord(object : Dictionary.WordVisitor {
            override fun visit(word: String, probability: Int) {
                if (probability < MIN_PROBABILITY) return
                val lower = word.lowercase(Locale.ROOT)
                var letters = 0
                var i = 0
                while (i < lower.length) {
                    val cp = lower.codePointAt(i)
                    if (Character.isLetter(cp)) letters++
                    else if (cp != '\''.code && cp != 0x2019) return // spaces, digits, emojis, ...
                    i += Character.charCount(cp)
                }
                if (letters < 2 || letters > MAX_WORD_LENGTH) return
                val old = best[lower]
                if (old == null || probability > old) best[lower] = probability
            }
        })
        var entries = best.entries.toList()
        if (entries.size > MAX_WORDS) entries = entries.sortedByDescending { it.value }.take(MAX_WORDS)
        val words = Array(entries.size) { entries[it].key }
        val probs = IntArray(entries.size) { entries[it].value }
        val seqs = Array(entries.size) { n ->
            val w = words[n]
            val list = ArrayList<Int>(w.length)
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                if (Character.isLetter(cp)) list.add(cp)
                i += Character.charCount(cp)
            }
            list.toIntArray()
        }
        val groups = HashMap<Int, MutableList<Int>>()
        for (n in seqs.indices) groups.getOrPut(seqs[n][0]) { ArrayList() }.add(n)
        return Vocab(words, seqs, probs, groups.mapValues { it.value.toIntArray() })
    }

    /** Suggestions for the swipe in [composedData] (best first); empty while the vocabulary is still loading. */
    @JvmStatic
    fun suggest(dict: Dictionary, composedData: ComposedData, keyboard: Keyboard): List<SuggestedWordInfo> {
        val vocab = vocabs[dict]
        if (vocab == null) {
            prepare(dict)
            return emptyList()
        }
        val pointers = composedData.mInputPointers
        val count = pointers.pointerSize
        if (count < 2) return emptyList()
        val keyW = max(1f, keyboard.mMostCommonKeyWidth.toFloat())

        val px = FloatArray(count) { pointers.xCoordinates[it].toFloat() }
        val py = FloatArray(count) { pointers.yCoordinates[it].toFloat() }
        val ux = FloatArray(SAMPLES)
        val uy = FloatArray(SAMPLES)
        val pathLength = resample(px, py, count, ux, uy)
        if (pathLength < keyW * 1.2f) return emptyList() // a tap or a tiny wiggle, not a swipe
        val startX = ux[0]; val startY = uy[0]
        val endX = ux[SAMPLES - 1]; val endY = uy[SAMPLES - 1]

        // key centres, looked up lazily; null = the layout has no key for that letter
        val centres = HashMap<Int, FloatArray?>()
        fun centre(cp: Int): FloatArray? = centres.getOrPut(cp) {
            var key = keyboard.getKey(cp) ?: keyboard.getKey(Character.toUpperCase(cp))
            if (key == null) {
                // accented letter without its own key: use the plain letter's key
                val plain = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
                    .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                if (plain.isNotEmpty()) key = keyboard.getKey(plain.codePointAt(0))
            }
            key?.hitBox?.let { floatArrayOf((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
        }

        val startRadius = keyW * 1.9f
        val endRadius = keyW * 2.1f
        val ix = FloatArray(MAX_WORD_LENGTH + 1)
        val iy = FloatArray(MAX_WORD_LENGTH + 1)
        val sx = FloatArray(SAMPLES)
        val sy = FloatArray(SAMPLES)

        val bestScore = FloatArray(MAX_RESULTS) { Float.MAX_VALUE }
        val bestIndex = IntArray(MAX_RESULTS) { -1 }

        for ((firstCp, candidates) in vocab.byFirst) {
            val c0 = centre(firstCp) ?: continue
            if (hypot(c0[0] - startX, c0[1] - startY) > startRadius) continue
            for (n in candidates) {
                val seq = vocab.seqs[n]
                // ideal path through the key centres (letters pressed twice in a row count once)
                var m = 0
                var ok = true
                var previous = -1
                for (cp in seq) {
                    if (cp == previous) continue
                    previous = cp
                    val c = centre(cp)
                    if (c == null) { ok = false; break }
                    ix[m] = c[0]; iy[m] = c[1]; m++
                }
                if (!ok || m < 2) continue
                if (hypot(ix[m - 1] - endX, iy[m - 1] - endY) > endRadius) continue
                var idealLength = 0f
                for (k in 1 until m) idealLength += hypot(ix[k] - ix[k - 1], iy[k] - iy[k - 1])
                if (idealLength <= 0f) continue
                val ratio = pathLength / idealLength
                if (ratio < 0.45f || ratio > 2.6f) continue
                resample(ix, iy, m, sx, sy)
                var sum = 0f
                val limit = SAMPLES * 1.4f * keyW
                for (k in 0 until SAMPLES) {
                    sum += hypot(sx[k] - ux[k], sy[k] - uy[k])
                    if (sum > limit) break
                }
                if (sum > limit) continue
                val shape = sum / (SAMPLES * keyW)
                // every letter of the word should lie close to the swiped path
                var coverage = 0f
                for (k in 0 until m) {
                    var nearest = Float.MAX_VALUE
                    for (q in 0 until SAMPLES) {
                        val d = hypot(ix[k] - ux[q], iy[k] - uy[q])
                        if (d < nearest) nearest = d
                    }
                    coverage += nearest
                }
                coverage /= m * keyW
                // common words win ties between similar shapes
                val score = shape + 0.8f * coverage + (1f - vocab.probs[n].coerceIn(0, 255) / 255f) * 0.4f
                insert(bestScore, bestIndex, score, n)
            }
        }

        val result = ArrayList<SuggestedWordInfo>()
        for (r in 0 until MAX_RESULTS) {
            val n = bestIndex[r]
            if (n < 0) break
            val points = (1_000_000 - bestScore[r] * 100_000f).toInt()
            result.add(
                SuggestedWordInfo(
                    vocab.words[n], "", points, SuggestedWordInfo.KIND_CORRECTION, dict,
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                )
            )
        }
        return result
    }

    private fun insert(scores: FloatArray, indexes: IntArray, score: Float, index: Int) {
        if (score >= scores[scores.size - 1]) return
        var pos = scores.size - 1
        while (pos > 0 && scores[pos - 1] > score) {
            scores[pos] = scores[pos - 1]
            indexes[pos] = indexes[pos - 1]
            pos--
        }
        scores[pos] = score
        indexes[pos] = index
    }

    /** Writes [SAMPLES] points spaced evenly along the polyline into [ox]/[oy]; returns the polyline length. */
    private fun resample(px: FloatArray, py: FloatArray, m: Int, ox: FloatArray, oy: FloatArray): Float {
        var total = 0f
        for (i in 1 until m) total += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        if (m < 2 || total <= 0f) {
            for (k in 0 until SAMPLES) { ox[k] = px[0]; oy[k] = py[0] }
            return 0f
        }
        val step = total / (SAMPLES - 1)
        var seg = 1
        var segStart = 0f
        var segLen = hypot(px[1] - px[0], py[1] - py[0])
        for (k in 0 until SAMPLES) {
            val target = k * step
            while (seg < m - 1 && target > segStart + segLen) {
                segStart += segLen
                seg++
                segLen = hypot(px[seg] - px[seg - 1], py[seg] - py[seg - 1])
            }
            val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
            ox[k] = px[seg - 1] + (px[seg] - px[seg - 1]) * t
            oy[k] = py[seg - 1] + (py[seg] - py[seg - 1]) * t
        }
        return total
    }
}
