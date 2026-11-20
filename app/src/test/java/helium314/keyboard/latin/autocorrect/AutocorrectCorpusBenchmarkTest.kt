// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Category scores for the on-device corrector.
 * Public misspellings are the short well-known ones (Wikipedia-style), not a copied word list.
 * The rest are synthetic QWERTY errors. A correct word, name, or literal must stay.
 */
class AutocorrectCorpusBenchmarkTest {
    private val words = ProximityAutocorrect.ListWords(LEXICON.map { ProximityAutocorrect.VocabWord(it, if (it.length <= 4) 200 else 140) })

    @Test fun categoriesFalseRateAndLatency() {
        val buckets = LinkedHashMap<String, Bucket>()
        fun add(category: String, typed: String, expected: String, previous: String? = null, sentenceStart: Boolean = false) {
            buckets.getOrPut(category) { Bucket() }.cases.add(Case(typed, expected, previous, sentenceStart))
        }
        for ((typed, expected) in PUBLIC_MISSPELLINGS) add("public-misspelling", typed, expected)
        for ((typed, expected) in CONTRACTIONS) add("contraction", typed, expected)
        for ((prev, typed, expected) in CONTEXT) add("context", typed, expected, previous = prev)
        add("capital", "i", "I")
        add("capital", "hello", "Hello", sentenceStart = true)

        val rng = java.util.Random(11)
        val pool = LEXICON.filter { it.length in 5..10 && it.all { ch -> ch.isLetter() && ch.isLowerCase() } }
        var fat = 0
        var trans = 0
        var doubled = 0
        var omitted = 0
        for (word in pool) {
            if (fat < 250) neighborSlip(word, rng)?.let { fat++; add("fat-finger", it, word) }
            if (trans < 200) transposition(word, rng)?.let { trans++; add("transposition", it, word) }
            if (doubled < 120) doubleLetter(word, rng)?.let { doubled++; add("doubled-letter", it, word) }
            if (omitted < 120) omitLetter(word, rng)?.let { omitted++; add("omitted-letter", it, word) }
        }
        for ((typed, expected) in RUN_ONS) add("run-on", typed, expected)

        val guard = ArrayList<String>()
        guard.addAll(pool.take(180))
        guard.addAll(listOf(
            "jordan", "taylor", "zoe", "nora", "jonathan", "elizabeth",
            "lol", "brb", "yeet", "omw",
            "https://example.com", "zen@example.com", "@zen", "12345", "3.14",
            "iPhone", "eBay", "McDonald",
        ))

        val samples = ArrayList<Long>()
        for ((name, bucket) in buckets) {
            for (case in bucket.cases) {
                val started = System.nanoTime()
                val got = fix(case.typed, case.previous, case.sentenceStart)
                samples.add(System.nanoTime() - started)
                val ok = got.equals(case.expected, ignoreCase = true)
                if (ok) bucket.fixed++ else bucket.misses.add("${case.typed}→$got (want ${case.expected})")
            }
            val rate = bucket.fixed.toDouble() / bucket.cases.size
            println("AUTOCORRECT_CATEGORY $name n=${bucket.cases.size} fixed=${bucket.fixed} rate=${"%.3f".format(rate)}")
            if (bucket.misses.isNotEmpty()) println("AUTOCORRECT_MISS $name ${bucket.misses.take(8)}")
        }
        var falseCount = 0
        val falseWords = ArrayList<String>()
        for (typed in guard) {
            val started = System.nanoTime()
            val got = ProximityAutocorrect.correct(typed, words)
            samples.add(System.nanoTime() - started)
            if (got != null && !got.equals(typed, ignoreCase = true)) {
                falseCount++
                falseWords.add("$typed→$got")
            }
        }
        val sorted = samples.toLongArray()
        Arrays.sort(sorted)
        val p50 = sorted[sorted.size / 2] / 1_000_000.0
        val p95 = sorted[(sorted.size * 95) / 100] / 1_000_000.0
        val falseRate = falseCount.toDouble() / guard.size
        println("AUTOCORRECT_LATENCY n=${sorted.size} p50_ms=${"%.4f".format(p50)} p95_ms=${"%.4f".format(p95)}")
        println("AUTOCORRECT_FALSE n=${guard.size} false=$falseCount rate=${"%.4f".format(falseRate)} $falseWords")

        assertTrue(buckets.getValue("public-misspelling").rate >= 0.90)
        assertTrue(buckets.getValue("contraction").rate >= 0.95)
        assertTrue(buckets.getValue("context").rate == 1.0)
        assertTrue(buckets.getValue("capital").rate == 1.0)
        assertTrue(buckets.getValue("fat-finger").rate >= 0.85, "fat-finger ${buckets.getValue("fat-finger").rate}")
        assertTrue(buckets.getValue("transposition").rate >= 0.70, "transposition ${buckets.getValue("transposition").rate}")
        assertTrue(buckets.getValue("omitted-letter").rate >= 0.80, "omitted ${buckets.getValue("omitted-letter").rate}")
        assertTrue(buckets.getValue("run-on").rate == 1.0)
        assertEquals(0, falseCount, falseWords.toString())
        assertTrue(p95 < 1.0, "p95 $p95 ms")
    }

    private fun fix(typed: String, previous: String?, sentenceStart: Boolean): String {
        return ProximityAutocorrect.correct(typed, words, previous = previous, sentenceStart = sentenceStart)
            ?: ProximityAutocorrect.missingSpace(typed, words)
            ?: typed
    }

    private fun neighborSlip(word: String, rng: java.util.Random): String? {
        val chars = word.toCharArray()
        val i = 1 + rng.nextInt(word.length - 2)
        val next = neighbor(chars[i]) ?: return null
        if (next == chars[i]) return null
        chars[i] = next
        val typed = String(chars)
        return if (typed == word) null else typed
    }

    private fun transposition(word: String, rng: java.util.Random): String? {
        if (word.length < 4) return null
        val chars = word.toCharArray()
        val i = rng.nextInt(word.length - 1)
        val tmp = chars[i]
        chars[i] = chars[i + 1]
        chars[i + 1] = tmp
        val typed = String(chars)
        return if (typed == word) null else typed
    }

    private fun doubleLetter(word: String, rng: java.util.Random): String? {
        val i = rng.nextInt(word.length)
        return word.substring(0, i) + word[i] + word.substring(i)
    }

    private fun omitLetter(word: String, rng: java.util.Random): String? {
        if (word.length < 5) return null
        val i = 1 + rng.nextInt(word.length - 2)
        return word.removeRange(i, i + 1)
    }

    private fun neighbor(ch: Char): Char? {
        for (row in ROWS) {
            val i = row.indexOf(ch)
            if (i >= 0) return if (i + 1 < row.length) row[i + 1] else row[i - 1]
        }
        return null
    }

    private class Bucket {
        val cases = ArrayList<Case>()
        var fixed = 0
        val misses = ArrayList<String>()
        val rate: Double get() = if (cases.isEmpty()) 0.0 else fixed.toDouble() / cases.size
    }

    private data class Case(val typed: String, val expected: String, val previous: String?, val sentenceStart: Boolean)

    companion object {
        private val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val PUBLIC_MISSPELLINGS = listOf(
            "teh" to "the", "recieve" to "receive", "becuase" to "because", "seperate" to "separate",
            "definately" to "definitely", "thier" to "their", "wich" to "which", "untill" to "until",
            "occured" to "occurred", "beleive" to "believe", "freind" to "friend", "adress" to "address",
            "tommorow" to "tomorrow", "enviroment" to "environment", "goverment" to "government",
            "seige" to "siege", "calender" to "calendar", "relevent" to "relevant", "independant" to "independent",
        )
        private val CONTRACTIONS = listOf(
            "dont" to "don't", "cant" to "can't", "im" to "I'm", "youre" to "you're",
            "thats" to "that's", "whats" to "what's", "youll" to "you'll", "wouldve" to "would've",
        )
        private val CONTEXT = listOf(
            Triple("over", "their", "there"),
            Triple("of", "there", "their"),
            Triple("know", "its", "it's"),
            Triple("away", "form", "from"),
            Triple("a", "from", "form"),
        )
        private val RUN_ONS = listOf(
            "iamgoing" to "I am going",
            "thankyou" to "thank you",
            "whatbtime" to "what time",
        )
        private val LEXICON = """
            the hello help and because receive separate definitely their there don't can't i'm i've
            to of in on cat car ok lol auto correct zen yeet ill its it me be is was for you that with
            this have they but not what your can time would like just know good people about from word
            been were said each which how will make into other than then them some could first water
            call who now find long down day did get come made may part over new take only work place
            year live back give most very after our name think great where through much before line
            right too mean old any same tell boy want show also around form three small set put end
            well large must big even such here why ask men need land home move try kind hand again
            change off play spell air away house point page letter answer found study still learn
            world high every near add food last keep tree never start city earth eye light head story
            left few while might close hard open begin life always those both paper group often run
            until side feet night walk white sea grow book hear stop idea enough eat face watch far
            real almost let above girl young talk soon list song leave family body music color stand
            sun fish area mark dog horse room since ever piece usually easy heard order red door sure
            become top ship today during short better best low hours black whole early listen wind
            rock fast hold step true table north money map farm draw voice seen cold plan notice south
            sing ground fall king town unit figure field wood fire certain going having something
            tomorrow thank gonna am see go together jonathan elizabeth occurred believe friend address
            environment government siege calendar relevant independent
            jordan taylor zoe nora amber bailey carter dylan finn harper logan mason owen parker
            quinn riley sage umber violet wesley xander yara
        """.trimIndent().split(Regex("\\s+")).map { it.lowercase() }.distinct()
    }
}
