// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.autocorrect.ProximityAutocorrect
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
        val byLetters: Map<String, Int>,
    )

    private val vocabs: MutableMap<Dictionary, Vocab> = Collections.synchronizedMap(WeakHashMap())
    private val loading: MutableSet<Dictionary> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Dictionary, Boolean>()))

    /** The dictionary the user is typing against. Built on first use so a correction is not skipped while a background load is still running. */
    private fun vocabOf(dict: Dictionary): Vocab? {
        vocabs[dict]?.let { return it }
        if (loading.contains(dict)) {
            // A full dictionary is already loading. Don't build it again on the key path.
            val deadline = System.nanoTime() + 80_000_000L
            while (System.nanoTime() < deadline) {
                vocabs[dict]?.let { return it }
                if (!loading.contains(dict)) break
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
            return vocabs[dict]
        }
        synchronized(vocabs) {
            vocabs[dict]?.let { return it }
            if (loading.contains(dict)) return null
            return try {
                buildVocab(dict).also { vocabs[dict] = it }
            } catch (_: Throwable) {
                null
            }
        }
    }

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
        val byLetters = HashMap<String, Int>(words.size)
        for (n in words.indices) {
            val key = helium314.keyboard.latin.autocorrect.AutocorrectPolicy.lettersOf(words[n])
            if (key.isEmpty()) continue
            val old = byLetters[key]
            if (old == null || probs[n] > probs[old]) byLetters[key] = n
        }
        return Vocab(words, seqs, probs, groups.mapValues { it.value.toIntArray() }, byLetters)
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

    /**
     * A clearly-intended word for a mistyped one: same length, a few letters hit on a neighbouring key
     * (or two neighbouring letters swapped). Returns null unless exactly one common word fits well.
     */
    @JvmStatic
    fun neighbourKeyCorrection(dict: Dictionary, typed: String, keyboard: Keyboard): String? {
        val vocab = vocabOf(dict) ?: return null
        val lower = typed.lowercase(Locale.ROOT)
        val typedCps = IntArray(lower.codePointCount(0, lower.length))
        var i = 0
        var k = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            if (!Character.isLetter(cp)) return null
            typedCps[k++] = cp
            i += Character.charCount(cp)
        }
        val len = typedCps.size
        if (len < 5 || len > MAX_WORD_LENGTH) return null
        val keyW = max(1f, keyboard.mMostCommonKeyWidth.toFloat())
        val maxSubs = (len + 1) / 2 - 1
        val centres = HashMap<Int, FloatArray?>()
        fun centre(cp: Int): FloatArray? = centres.getOrPut(cp) {
            (keyboard.getKey(cp) ?: keyboard.getKey(Character.toUpperCase(cp)))?.hitBox
                ?.let { floatArrayOf((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
        }
        var bestScore = Float.MAX_VALUE
        var secondScore = Float.MAX_VALUE
        var bestWord: String? = null
        var bestProb = 0
        for (n in vocab.seqs.indices) {
            val seq = vocab.seqs[n]
            if (seq.size != len) continue
            val prob = vocab.probs[n]
            if (prob < 60) continue
            var cost = 0f
            var subs = 0
            var ok = true
            var p = 0
            while (p < len) {
                if (seq[p] == typedCps[p]) { p++; continue }
                // adjacent letters swapped ("teh" -> "the")
                if (p + 1 < len && seq[p] == typedCps[p + 1] && seq[p + 1] == typedCps[p]) {
                    subs++; cost += 0.8f; p += 2; continue
                }
                val a = centre(seq[p]); val b = centre(typedCps[p])
                if (a == null || b == null) { ok = false; break }
                val d = hypot(a[0] - b[0], a[1] - b[1]) / keyW
                if (d > 1.55f) { ok = false; break }
                subs++; cost += d; p++
                if (subs > maxSubs) { ok = false; break }
            }
            if (!ok || subs == 0) continue
            val score = cost + (1f - prob.coerceIn(0, 255) / 255f) * 0.8f
            if (score < bestScore) {
                secondScore = bestScore; bestScore = score; bestWord = vocab.words[n]; bestProb = prob
            } else if (score < secondScore) {
                secondScore = score
            }
        }
        if (bestWord == null || bestProb < 80) return null
        if (secondScore - bestScore < 0.5f) return null // not clearly one word
        return bestWord
    }

    /**
     * Proximity edit-distance correction for a mistyped word, including shifted fat-finger
     * input such as "xoredrt" → "correct" and contractions such as "dont" → "don't".
     * Uses this layout's key centres when the letter has a key, otherwise QWERTY.
     * Returns null while the vocabulary is still loading.
     */
    @JvmStatic
    @JvmOverloads
    fun proximityCorrection(
        dict: Dictionary,
        typed: String,
        keyboard: Keyboard,
        sentenceStart: Boolean,
        pointers: helium314.keyboard.latin.common.InputPointers? = null,
    ): String? {
        val opened = openSource(dict, keyboard) ?: return null
        val keyW = max(1f, keyboard.mMostCommonKeyWidth.toFloat())
        val touches = touchPoints(typed, pointers, keyW)
        return ProximityAutocorrect.correct(
            typed, opened.first, opened.second,
            kept = false, sentenceStart = sentenceStart,
            touches = touches, centreOf = { ch -> centreInKeyWidths(keyboard, ch, keyW) },
        )
    }

    /** "gobi" + "g" → "going". Null until the vocabulary has loaded, or when the tokens should stay apart. */
    @JvmStatic
    fun spaceSlip(dict: Dictionary, previous: String, fragment: String, keyboard: Keyboard?): String? {
        val opened = openSource(dict, keyboard) ?: return null
        return ProximityAutocorrect.spaceSlip(previous, fragment, opened.first, opened.second)
    }

    /** "isgoing" → "is going". */
    @JvmStatic
    fun missingSpace(dict: Dictionary, typed: String, keyboard: Keyboard): String? {
        val opened = openSource(dict, keyboard) ?: return null
        return ProximityAutocorrect.missingSpace(typed, opened.first, opened.second)
    }

    /** Touch points in key-width units, one per typed letter. Unknown presses stay null. */
    private fun touchPoints(typed: String, pointers: helium314.keyboard.latin.common.InputPointers?, keyW: Float): Array<FloatArray?>? {
        if (pointers == null || keyW <= 0f) return null
        val n = typed.length
        if (pointers.pointerSize < n) return null
        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates
        val out = arrayOfNulls<FloatArray>(n)
        var any = false
        for (i in 0 until n) {
            val x = xs[i]
            val y = ys[i]
            if (x < 0 || y < 0) continue
            out[i] = floatArrayOf(x / keyW, y / keyW)
            any = true
        }
        return if (any) out else null
    }

    private fun centreInKeyWidths(keyboard: Keyboard, ch: Char, keyW: Float): FloatArray? {
        val cp = ch.code
        val key = keyboard.getKey(cp) ?: keyboard.getKey(Character.toUpperCase(cp))
        val box = key?.hitBox
        if (box != null && keyW > 0f) {
            return floatArrayOf((box.left + box.right) / 2f / keyW, (box.top + box.bottom) / 2f / keyW)
        }
        return helium314.keyboard.latin.autocorrect.KeyGeometry.centre(ch)
    }

    private fun openSource(
        dict: Dictionary,
        keyboard: Keyboard?,
    ): Pair<ProximityAutocorrect.WordSource, (Char, Char) -> Float>? {
        val vocab = vocabOf(dict) ?: return null
        val keyW = max(1f, keyboard?.mMostCommonKeyWidth?.toFloat() ?: 1f)
        val centres = HashMap<Char, FloatArray?>()
        fun centre(ch: Char): FloatArray? = centres.getOrPut(ch) {
            if (keyboard == null) return@getOrPut null
            val cp = ch.code
            val key = keyboard.getKey(cp) ?: keyboard.getKey(Character.toUpperCase(cp))
            key?.hitBox?.let { floatArrayOf((it.left + it.right) / 2f, (it.top + it.bottom) / 2f) }
        }
        fun keyDistance(a: Char, b: Char): Float {
            if (a == b) return 0f
            val ca = centre(a)
            val cb = centre(b)
            if (ca == null || cb == null) return helium314.keyboard.latin.autocorrect.KeyGeometry.qwertyDistance(a, b)
            return hypot(ca[0] - cb[0], ca[1] - cb[1]) / keyW
        }
        val source = object : ProximityAutocorrect.WordSource {
            override val size: Int get() = vocab.words.size
            override fun word(index: Int) = vocab.words[index]
            override fun probability(index: Int) = vocab.probs[index]
            override fun exact(letters: String): Pair<String, Int>? {
                val n = vocab.byLetters[letters] ?: return null
                return vocab.words[n] to vocab.probs[n]
            }
            override fun indexesNear(first: Char, distance: (Char, Char) -> Float): IntArray {
                val out = ArrayList<Int>()
                val f = helium314.keyboard.latin.autocorrect.KeyGeometry.baseLetter(first)
                for ((cp, arr) in vocab.byFirst) {
                    if (cp > 0xFFFF) continue
                    val ch = helium314.keyboard.latin.autocorrect.KeyGeometry.baseLetter(cp.toChar())
                    if (distance(f, ch) <= 2.3f) {
                        for (n in arr) out.add(n)
                    }
                }
                return out.toIntArray()
            }
        }
        return source to ::keyDistance
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
