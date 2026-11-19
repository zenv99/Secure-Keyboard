// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Corpus for the on-device corrector. "Before" is the previous same-length neighbour matcher
 * (what missed "xoredrt") and an aggressive nearest-word matcher (what rewrites valid words).
 * "After" is [ProximityAutocorrect].
 */
class ProximityAutocorrectTest {
    private val vocab = ProximityAutocorrect.ListWords(VOCAB)
    private val byWord = VOCAB.associate { it.word.lowercase() to it.probability }

    @Test fun xoredrtCorrectsToCorrect() {
        assertEquals("correct", correct("xoredrt"))
    }

    @Test fun fatFingerAndContractions() {
        assertEquals("correct", correct("xoredrt"))
        assertEquals("the", correct("teh"))
        assertEquals("receive", correct("recieve"))
        assertEquals("because", correct("becuase"))
        assertEquals("and", correct("adn"))
        assertEquals("hello", correct("hellp"))
        assertEquals("definitely", correct("definately"))
        assertEquals("separate", correct("seperate"))
        assertEquals("their", correct("thier"))
        assertEquals("direct", correct("direcr"))
        assertEquals("correct", correct("corret"))
        assertEquals("don't", correct("dont"))
        assertEquals("I'm", correct("im"))
        assertEquals("can't", correct("cant"))
        assertEquals("I've", correct("ive"))
        assertEquals("Don't", correct("Dont"))
        assertEquals("DON'T", correct("DONT"))
        assertEquals("Don't", correct("dont", sentenceStart = true))
        assertEquals("I'M", correct("IM"))
        assertEquals("want", correct("wnat"))
        assertEquals("that", correct("taht"))
    }

    @Test fun validWordsNamesSlangAndLiteralsStay() {
        for (word in listOf(
            "cat", "ok", "lol", "the", "auto", "correct", "because", "zen", "yeet", "ill", "its", "to", "on", "in",
        )) {
            assertNull(correct(word), word)
        }
        assertNull(correct("brb"), "brb")
        assertNull(correct("omw"), "omw")
        assertNull(correct("https://example.com"))
        assertNull(correct("a@b.co"))
        assertNull(correct("12345"))
        assertNull(correct("3.14"))
        assertEquals("the.", AutocorrectSpacing.join("the ", "."))
        assertEquals("the ", AutocorrectSpacing.join("the", " "))
        assertEquals("hello,", AutocorrectSpacing.join("hello", ","))
        assertEquals("hello!", AutocorrectSpacing.join("hello ", "!"))
    }

    @Test fun keptWordsAreNotCorrectedEvenWhenTheyLookLikeTypos() {
        assertNull(ProximityAutocorrect.correct("xoredrt", vocab, kept = true))
        assertNull(ProximityAutocorrect.correct("teh", vocab, kept = true))
        assertNull(ProximityAutocorrect.correct("dont", vocab, kept = true))
    }

    @Test fun revertLearningIsIgnoredInNoLearningFields() {
        assertFalse(AutocorrectPolicy.keepTypedWord(false, true, true, false, blocksPersonalization = true))
        assertTrue(AutocorrectPolicy.keepTypedWord(false, true, false, false, blocksPersonalization = false))
        assertTrue(AutocorrectPolicy.keepTypedWord(false, false, true, false, blocksPersonalization = false))
        assertTrue(AutocorrectPolicy.keepTypedWord(true, false, false, false, blocksPersonalization = true))
        assertTrue(AutocorrectPolicy.keepTypedWord(false, false, false, true, blocksPersonalization = true))
        assertFalse(AutocorrectPolicy.isLiteralToken("dont"))
        assertTrue(AutocorrectPolicy.suppressCorrection("tired", ".", typedIsKnown = false, forced = false))
        assertTrue(AutocorrectPolicy.suppressCorrection("I'm", ".", typedIsKnown = true, forced = false))
        assertFalse(AutocorrectPolicy.suppressCorrection("omw", "On my way!", typedIsKnown = false, forced = true))
        assertTrue(AutocorrectPolicy.isLiteralToken("zen@example.com"))
        assertTrue(AutocorrectPolicy.isLiteralToken("v1.0"))
    }

    @Test fun acceptedTypedWordRoundTripAndStopsCorrection() {
        val dir = Files.createTempDirectory("accepted-typed").toFile()
        AcceptedTypedWords.ensureLoaded(dir)
        assertFalse(AcceptedTypedWords.remembers("xoredrt"))
        AcceptedTypedWords.remember(dir, "xoredrt")
        assertTrue(AcceptedTypedWords.remembers("Xoredrt"))
        assertEquals("xoredrt", dir.resolve(AcceptedTypedWords.FILE_NAME).readLines().single())
        assertNull(ProximityAutocorrect.correct("xoredrt", vocab, kept = AcceptedTypedWords.remembers("xoredrt")))
        // A different word is still corrected.
        assertEquals("correct", correct("corret"))
    }

    @Test fun measuredBeforeAndAfter() {
        val typos = TYPOS
        val keep = KEEP
        val beforeNeighbour = score("neighbour", typos, keep) { legacyNeighbour(it) }
        val beforeAggressive = score("aggressive", typos, keep) { aggressive(it) }
        val started = System.nanoTime()
        val repeats = 30
        repeat(repeats) {
            for ((typed, _) in typos) correct(typed)
            for (typed in keep) correct(typed)
        }
        val keystrokes = (typos.sumOf { it.first.length } + keep.sumOf { it.length }) * repeats
        val msPerKeystroke = (System.nanoTime() - started) / keystrokes.toDouble() / 1_000_000.0
        val after = score("balanced", typos, keep) { correct(it) }

        println("AUTOCORRECT_BEFORE_NEIGHBOUR fix=${fmt(beforeNeighbour.fixRate)} false=${fmt(beforeNeighbour.falseRate)} " +
            "fixed=${beforeNeighbour.fixed}/${beforeNeighbour.typos} falseCount=${beforeNeighbour.falseCount}/${beforeNeighbour.keep}")
        println("AUTOCORRECT_BEFORE_AGGRESSIVE fix=${fmt(beforeAggressive.fixRate)} false=${fmt(beforeAggressive.falseRate)} " +
            "fixed=${beforeAggressive.fixed}/${beforeAggressive.typos} falseCount=${beforeAggressive.falseCount}/${beforeAggressive.keep}")
        println("AUTOCORRECT_AFTER fix=${fmt(after.fixRate)} false=${fmt(after.falseRate)} " +
            "fixed=${after.fixed}/${after.typos} falseCount=${after.falseCount}/${after.keep} " +
            "ms_per_keystroke=${"%.4f".format(msPerKeystroke)}")
        if (after.misses.isNotEmpty()) println("AUTOCORRECT_MISSES ${after.misses}")
        if (after.falseWords.isNotEmpty()) println("AUTOCORRECT_FALSE ${after.falseWords}")

        assertTrue(beforeNeighbour.fixRate < after.fixRate, "balanced corrector should fix more than same-length neighbour matching")
        assertTrue(after.falseRate < beforeAggressive.falseRate, "balanced corrector should false-correct less than nearest-word")
        assertTrue(after.fixRate >= 0.95, "fix rate ${after.fixRate}")
        assertEquals(0, after.falseCount, after.falseWords.toString())
        assertTrue(msPerKeystroke < 1.0, "latency $msPerKeystroke ms/keystroke")
        assertNull(legacyNeighbour("xoredrt"), "the old matcher does not recover xoredrt")
        assertEquals("correct", correct("xoredrt"))
    }

    @Test fun shortWordsAndSpaceSlipsFromZen() {
        val words = ProximityAutocorrect.ListWords(VOCAB + listOf(
            w("going", 210), w("having", 170), w("something", 190), w("today", 160),
        ))
        assertEquals("is", ProximityAutocorrect.correct("iw", words))
        assertEquals("the", ProximityAutocorrect.correct("thw", words))
        assertEquals("going", ProximityAutocorrect.spaceSlip("gobi", "g", words))
        assertEquals("having", ProximityAutocorrect.spaceSlip("havi", "g", words))
        assertEquals("something", ProximityAutocorrect.spaceSlip("somethi", "g", words))
        assertEquals("is going", ProximityAutocorrect.missingSpace("isgoing", words))
        assertNull(ProximityAutocorrect.spaceSlip("the", "cat", words))
        assertNull(ProximityAutocorrect.missingSpace("something", words))
        assertNull(ProximityAutocorrect.missingSpace("today", words))
    }

    @Test fun fatFingersAndRunOns() {
        val words = ProximityAutocorrect.ListWords(VOCAB + listOf(
            w("hello", 220), w("good", 200), w("thanks", 180), w("going", 210),
            w("tomorrow", 160), w("thank", 150), w("gonna", 140), w("time", 170),
            w("what", 160), w("way", 150), w("my", 180), w("on", 190), w("see", 160), w("go", 180),
            w("am", 170), w("you", 200), w("something", 190), w("today", 160),
            w("together", 150), w("jonathan", 80), w("elizabeth", 80),
        ))
        val hwllo = ProximityAutocorrect.correct("hwllo", words)
        assertEquals("hello", hwllo, "cost=${ProximityAutocorrect.alignmentCost("hwllo", "hello", KeyGeometry::qwertyDistance, 3f)}")
        assertEquals("the", ProximityAutocorrect.correct("rhe", words))
        assertEquals("good", ProximityAutocorrect.correct("gpod", words))
        assertEquals("thanks", ProximityAutocorrect.correct("thwnks", words))
        assertEquals("is going", ProximityAutocorrect.spaceSlip("gobi", "g", words)?.let { "is $it" }
            ?: ProximityAutocorrect.spaceSlip("gobi", "g", words))
        assertEquals("going", ProximityAutocorrect.spaceSlip("gobi", "g", words))
        assertEquals("I am going", ProximityAutocorrect.missingSpace("iamgoing", words))
        assertEquals("see you tomorrow", ProximityAutocorrect.missingSpace("seeyoutomorrow", words))
        assertEquals("thank you", ProximityAutocorrect.missingSpace("thankyou", words))
        assertEquals("on my way", ProximityAutocorrect.missingSpace("onmyway", words))
        assertEquals("what time", ProximityAutocorrect.missingSpace("whatbtime", words))
        assertEquals("gonna go", ProximityAutocorrect.missingSpace("gonnabgo", words))
        assertNull(ProximityAutocorrect.missingSpace("something", words))
        assertNull(ProximityAutocorrect.missingSpace("together", words))
        assertNull(ProximityAutocorrect.missingSpace("jonathan", words))
        assertNull(ProximityAutocorrect.correct("jonathan", words))
        // Touch nearer E than A makes hallo → hello. The key centres alone leave a/e too far.
        val e = KeyGeometry.centre('e')!!
        val touches = arrayOfNulls<FloatArray>(5)
        touches[1] = floatArrayOf(e[0], e[1])
        assertNull(ProximityAutocorrect.correct("hallo", words))
        assertEquals("hello", ProximityAutocorrect.correct("hallo", words, touches = touches, centreOf = KeyGeometry::centre))
    }

    @Test fun largeCorpusStaysAccurateAndFast() {
        val base = VOCAB.map { it.word }.filter { it.length >= 4 && it.all { ch -> ch.isLetter() } }.distinct()
        val extras = COMMON.filter { it !in base }
        val all = (base + extras).distinct()
        val words = ProximityAutocorrect.ListWords(all.map { w(it, if (it.length <= 5) 180 else 120) })
        val typos = ArrayList<Pair<String, String>>()
        val rng = java.util.Random(7)
        for (word in all) {
            if (typos.size >= 320) break
            if (word.length < 4) continue
            val chars = word.toCharArray()
            val i = 1 + rng.nextInt(word.length - 2)
            val neighbor = neighborOf(chars[i])
            if (neighbor != null && neighbor != chars[i]) {
                chars[i] = neighbor
                typos.add(String(chars) to word)
            }
            if (typos.size < 320 && word.length >= 5) {
                val swapped = word.toCharArray()
                val j = rng.nextInt(word.length - 1)
                val tmp = swapped[j]
                swapped[j] = swapped[j + 1]
                swapped[j + 1] = tmp
                if (String(swapped) != word) typos.add(String(swapped) to word)
            }
        }
        val keep = all.take(520)
        assertTrue(typos.size >= 300, "typos ${typos.size}")
        assertTrue(keep.size >= 500, "keep ${keep.size}")
        val started = System.nanoTime()
        var fixed = 0
        for ((typed, expected) in typos) {
            val got = ProximityAutocorrect.correct(typed, words) ?: typed
            if (got.equals(expected, ignoreCase = true)) fixed++
        }
        var falseCount = 0
        for (typed in keep) {
            val got = ProximityAutocorrect.correct(typed, words)
            if (got != null && !got.equals(typed, ignoreCase = true)) falseCount++
        }
        val ms = (System.nanoTime() - started) / (typos.size + keep.size).toDouble() / 1_000_000.0
        val fixRate = fixed.toDouble() / typos.size
        val falseRate = falseCount.toDouble() / keep.size
        println("AUTOCORRECT_CORPUS typos=${typos.size} fixed=$fixed rate=${"%.3f".format(fixRate)} " +
            "keep=${keep.size} false=$falseCount rate=${"%.4f".format(falseRate)} ms=$ms")
        assertTrue(fixRate >= 0.90, "fix rate $fixRate")
        assertTrue(falseRate < 0.01, "false rate $falseRate")
        assertTrue(ms < 1.0, "latency $ms")
    }

    private fun neighborOf(ch: Char): Char? {
        val row = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (r in row) {
            val i = r.indexOf(ch)
            if (i >= 0) return if (i + 1 < r.length) r[i + 1] else r[i - 1]
        }
        return null
    }

    private fun correct(typed: String, sentenceStart: Boolean = false): String? =
        ProximityAutocorrect.correct(typed, vocab, sentenceStart = sentenceStart)

    private fun score(name: String, typos: List<Pair<String, String>>, keep: List<String>, corrector: (String) -> String?): Metrics {
        var fixed = 0
        val misses = ArrayList<String>()
        for ((typed, expected) in typos) {
            val got = corrector(typed) ?: typed
            if (got.equals(expected, ignoreCase = true)) fixed++ else misses.add("$typed→$got (want $expected)")
        }
        var falseCount = 0
        val falseWords = ArrayList<String>()
        for (typed in keep) {
            val got = corrector(typed)
            if (got != null && !got.equals(typed, ignoreCase = true)) {
                falseCount++
                falseWords.add("$typed→$got")
            }
        }
        return Metrics(name, typos.size, fixed, keep.size, falseCount, misses, falseWords)
    }

    /** Previous fat-finger path: same length, adjacent keys only, no contractions, length >= 5. */
    private fun legacyNeighbour(typed: String): String? {
        val lower = typed.lowercase()
        if (lower.length < 5 || lower.any { !it.isLetter() }) return null
        if (lower in byWord) return null
        val maxSubs = (lower.length + 1) / 2 - 1
        var bestScore = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        var best: String? = null
        for (entry in VOCAB) {
            if (entry.probability < 80) continue
            val word = entry.word.lowercase()
            if (word.length != lower.length) continue
            var cost = 0f
            var subs = 0
            var ok = true
            var p = 0
            while (p < lower.length) {
                if (word[p] == lower[p]) { p++; continue }
                if (p + 1 < lower.length && word[p] == lower[p + 1] && word[p + 1] == lower[p]) {
                    subs++; cost += 0.8f; p += 2; continue
                }
                val d = KeyGeometry.qwertyDistance(word[p], lower[p])
                if (d > KeyGeometry.ADJACENT) { ok = false; break }
                subs++; cost += d; p++
                if (subs > maxSubs) { ok = false; break }
            }
            if (!ok || subs == 0) continue
            val score = cost + (1f - entry.probability.coerceIn(0, 255) / 255f) * 0.8f
            if (score < bestScore) {
                second = bestScore; bestScore = score; best = entry.word
            } else if (score < second) second = score
        }
        if (best == null) return null
        if (second - bestScore < 0.5f) return null
        return best
    }

    /** Over-eager nearest word: one edit away and more frequent, including when the typed word is valid. */
    private fun aggressive(typed: String): String? {
        val letters = AutocorrectPolicy.lettersOf(typed)
        if (letters.length !in 2..12 || AutocorrectPolicy.isLiteralToken(typed)) return null
        val typedProb = byWord[letters] ?: 0
        var best: ProximityAutocorrect.VocabWord? = null
        for (entry in VOCAB) {
            val word = AutocorrectPolicy.lettersOf(entry.word)
            if (word == letters) continue
            if (abs(word.length - letters.length) > 1) continue
            if (editDistance(letters, word) > 1) continue
            if (best == null || entry.probability > best.probability) best = entry
        }
        val chosen = best ?: return null
        if (chosen.probability <= typedProb + 10) return null
        return chosen.word
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                val sub = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + sub)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    dp[j] = min(dp[j], prev)
                }
                prev = cur
            }
        }
        return dp[b.length]
    }

    private fun fmt(value: Double) = "%.3f".format(value)

    private data class Metrics(
        val name: String,
        val typos: Int,
        val fixed: Int,
        val keep: Int,
        val falseCount: Int,
        val misses: List<String>,
        val falseWords: List<String>,
    ) {
        val fixRate: Double get() = if (typos == 0) 0.0 else fixed.toDouble() / typos
        val falseRate: Double get() = if (keep == 0) 0.0 else falseCount.toDouble() / keep
    }

    companion object {
        private val VOCAB = listOf(
            w("correct", 210), w("direct", 180), w("ordered", 90), w("cored", 45),
            w("the", 250), w("hello", 180), w("help", 150), w("and", 250),
            w("because", 170), w("receive", 100), w("separate", 90), w("definitely", 80),
            w("their", 200), w("there", 190), w("don't", 160), w("can't", 140),
            w("i'm", 200), w("i've", 90), w("to", 250), w("of", 200), w("in", 210),
            w("on", 160), w("cat", 140), w("car", 190), w("ok", 100), w("lol", 80),
            w("auto", 70), w("zen", 60), w("yeet", 55), w("ill", 100), w("its", 120),
            w("it", 220), w("me", 150), w("be", 140), w("is", 230), w("was", 150),
            w("for", 180), w("you", 200), w("that", 190), w("with", 160), w("this", 150),
            w("have", 140), w("they", 90), w("but", 70), w("not", 110), w("what", 80),
            w("your", 80), w("can", 70), w("time", 60), w("would", 50), w("like", 50),
            w("just", 40), w("know", 50), w("good", 60), w("testing", 80), w("people", 40),
            w("about", 50), w("from", 70), w("word", 40), w("been", 40), w("were", 40),
            w("said", 40), w("each", 40), w("which", 40), w("how", 40), w("will", 60),
            w("make", 40), w("into", 40), w("other", 40), w("than", 40), w("then", 40),
            w("them", 40), w("some", 40), w("could", 40), w("first", 40), w("water", 40),
            w("call", 40), w("who", 40), w("oil", 40), w("now", 40), w("find", 40),
            w("long", 40), w("down", 40), w("day", 40), w("did", 40), w("get", 50),
            w("come", 40), w("made", 40), w("may", 40), w("part", 40), w("over", 40),
            w("new", 40), w("take", 40), w("only", 40), w("work", 40), w("place", 40),
            w("year", 40), w("live", 40), w("back", 40), w("give", 40), w("most", 40),
            w("very", 40), w("after", 40), w("our", 40), w("name", 40), w("think", 40),
            w("great", 40), w("where", 40), w("help", 150), w("through", 40), w("much", 40),
            w("before", 40), w("line", 40), w("right", 40), w("too", 40), w("mean", 40),
            w("old", 40), w("any", 40), w("same", 40), w("tell", 40), w("boy", 40),
            w("want", 40), w("show", 40), w("also", 40), w("around", 40), w("form", 40),
            w("three", 40), w("small", 40), w("set", 40), w("put", 40), w("end", 40),
            w("well", 40), w("large", 40), w("must", 40), w("big", 40), w("even", 40),
            w("such", 40), w("here", 50), w("why", 40), w("ask", 40), w("men", 40),
            w("need", 40), w("land", 40), w("home", 40), w("move", 40), w("try", 40),
            w("kind", 40), w("hand", 40), w("again", 40), w("change", 40), w("off", 40),
            w("play", 40), w("spell", 40), w("air", 40), w("away", 40), w("house", 40),
            w("point", 40), w("page", 40), w("letter", 40), w("answer", 40), w("found", 40),
            w("study", 40), w("still", 40), w("learn", 40), w("world", 40), w("high", 50),
            w("every", 40), w("near", 40), w("add", 40), w("food", 40), w("last", 40),
            w("keep", 40), w("tree", 40), w("never", 40), w("start", 40), w("city", 40),
            w("earth", 40), w("eye", 40), w("light", 40), w("head", 40), w("story", 40),
            w("left", 40), w("few", 40), w("while", 40), w("might", 40), w("close", 40),
            w("hard", 40), w("open", 40), w("begin", 40), w("life", 40), w("always", 40),
            w("those", 40), w("both", 40), w("paper", 40), w("group", 40), w("often", 40),
            w("run", 40), w("until", 40), w("side", 40), w("feet", 40), w("car", 190),
            w("night", 40), w("walk", 40), w("white", 40), w("sea", 40), w("grow", 40),
            w("book", 40), w("hear", 40), w("stop", 40), w("idea", 40), w("enough", 40),
            w("eat", 40), w("face", 40), w("watch", 40), w("far", 40), w("real", 40),
            w("almost", 40), w("let", 40), w("above", 40), w("girl", 40), w("young", 40),
            w("talk", 40), w("soon", 40), w("list", 40), w("song", 40), w("leave", 40),
            w("family", 40), w("body", 40), w("music", 40), w("color", 40), w("stand", 40),
            w("sun", 40), w("fish", 40), w("area", 40), w("mark", 40), w("dog", 40),
            w("horse", 40), w("room", 40), w("since", 40), w("ever", 40), w("piece", 40),
            w("usually", 40), w("easy", 40), w("heard", 40), w("order", 50), w("red", 40),
            w("door", 40), w("sure", 40), w("become", 40), w("top", 40), w("ship", 40),
            w("today", 40), w("during", 40), w("short", 40), w("better", 40), w("best", 40),
            w("low", 40), w("hours", 40), w("black", 40), w("whole", 40), w("early", 40),
            w("listen", 40), w("wind", 40), w("rock", 40), w("fast", 40), w("hold", 40),
            w("step", 40), w("true", 40), w("table", 40), w("north", 40), w("money", 40),
            w("map", 40), w("farm", 40), w("draw", 40), w("voice", 40), w("seen", 40),
            w("cold", 40), w("plan", 40), w("notice", 40), w("south", 40), w("sing", 40),
            w("ground", 40), w("fall", 40), w("king", 40), w("town", 40), w("unit", 40),
            w("figure", 40), w("field", 40), w("wood", 40), w("fire", 40), w("certain", 40),
        )

        private val TYPOS = listOf(
            "xoredrt" to "correct",
            "teh" to "the",
            "recieve" to "receive",
            "becuase" to "because",
            "adn" to "and",
            "hellp" to "hello",
            "definately" to "definitely",
            "seperate" to "separate",
            "thier" to "their",
            "direcr" to "direct",
            "corret" to "correct",
            "dont" to "don't",
            "im" to "I'm",
            "cant" to "can't",
            "ive" to "I've",
            "Dont" to "Don't",
            "DONT" to "DON'T",
            "taht" to "that",
            "wnat" to "want",
            "grwo" to "grow",
        )

        private val KEEP = listOf(
            "cat", "ok", "lol", "the", "auto", "correct", "because", "zen", "yeet", "ill", "its",
            "to", "on", "in", "of", "it", "me", "brb", "omw", "https://example.com", "a@b.co", "42",
            "hello", "people", "testing",
        )

        private fun w(word: String, probability: Int) = ProximityAutocorrect.VocabWord(word, probability)

        private val COMMON = """
            about above across after again against almost along already also always among another answer
            around asked available away because become before began behind being believe below beside
            between beyond board bring build built called cannot cause change children city close coming
            common company could country course create decided different doing done during early earth
            either enough every example family feeling finally first follow found friend front future
            general given going great group grown having heard heart hence house however human idea
            important include inside instead itself known large later learn least leave letter light
            likely little living local looking major making matter maybe means might model moment money
            morning mother moved music myself name nation nature nearly needs never night north nothing
            number often older once order other others outside paper parent party people period person
            phone place plant playing please point power present pretty problem public question quick
            quite rather reached reading ready really reason received remember report result return
            right river school second seems several shall short should shown similar simple since single
            small social something sometimes sound south space special spring stand start state still
            story strong student study stuff style summer system table taken talking their there these
            thing think those though thought three through today together toward tried trying under
            until using usually value various voice waiting wanted water weeks which while whole whose
            window within without woman women world would write written young
            alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november
            oscar papa quebec romeo sierra tango uniform victor whiskey xray yankee zulu
            amber bailey carter dylan finn harper indigo jordan kai logan mason nora owen parker
            quinn riley sage taylor umber violet wesley xander yara zoe
            apple banana cherry grape lemon mango olive peach pear plum berry melon
            monday tuesday wednesday thursday friday saturday sunday january february march april
            august september october november december spring summer autumn winter morning evening
            red blue green yellow purple orange black white brown pink gray silver gold
            one two three four five six seven eight nine ten eleven twelve twenty thirty forty fifty
            happy sad angry calm brave quiet loud fast slow soft hard warm cold wet dry
            book chair table phone lamp clock door floor wall roof garden street river ocean mountain
            cloud rain snow wind storm beach island forest desert valley bridge castle village city
            music dance paint photo movie story poem song voice guitar piano drum violin
            coffee water juice bread cheese butter sugar salt pepper honey milk rice pasta soup
            doctor teacher driver farmer baker artist writer singer dancer player coach judge
            happy little brown fox jumps over lazy dog while people watch from the garden gate
        """.trimIndent().split(Regex("\\s+")).map { it.lowercase() }.filter { it.length >= 3 }.distinct()
    }
}
