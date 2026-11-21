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
            "webull", "robinhood", "coinbase", "venmo", "grab",
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
        // The first calls load the geometry tables. Steady-state calls are the ones that matter.
        val steady = samples.drop(40).toLongArray()
        Arrays.sort(steady)
        val p50 = steady[steady.size / 2] / 1_000_000.0
        val p95 = steady[(steady.size * 95) / 100] / 1_000_000.0
        val falseRate = falseCount.toDouble() / guard.size
        println("AUTOCORRECT_LATENCY n=${steady.size} p50_ms=${"%.4f".format(p50)} p95_ms=${"%.4f".format(p95)}")
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
        // Steady-state p50 stays under a millisecond. p95 on a shared CI runner includes a GC pause.
        assertTrue(p50 < 1.0, "p50 $p50 ms")
        assertTrue(p95 < 15.0, "p95 $p95 ms")
    }

    @Test fun guardSetOfCommonWordsAndBrandsNeverChanges() {
        val common = COMMON_WORDS.split(Regex("\\s+")).map { it.lowercase() }.filter { it.length >= 2 }.distinct()
        assertTrue(common.size >= 500, "guard lexicon ${common.size}")
        val source = ProximityAutocorrect.ListWords(common.map { ProximityAutocorrect.VocabWord(it, 160) })
        val brands = listOf(
            "webull", "Webull", "WEBULL", "robinhood", "coinbase", "venmo", "grab",
            "shopee", "revolut", "whatsapp", "gonna", "lol", "omw", "idk", "Zen", "zen",
        )
        // Names and slang that must stay are in the dictionary, the way a user dictionary holds them.
        val kept = listOf("gonna", "lol", "omw", "idk", "zen")
        val withKept = ProximityAutocorrect.ListWords((common + kept).distinct().map {
            ProximityAutocorrect.VocabWord(it, 160)
        })
        var changed = 0
        val bad = ArrayList<String>()
        for (word in common) {
            val got = ProximityAutocorrect.correct(word, source)
            if (got != null && !got.equals(word, ignoreCase = true)) {
                changed++
                if (bad.size < 12) bad.add("$word→$got")
            }
        }
        for (brand in brands) {
            val got = ProximityAutocorrect.correct(brand, withKept)
            val split = ProximityAutocorrect.missingSpace(brand, withKept)
            if (got != null && !got.equals(brand, ignoreCase = true)) {
                changed++
                bad.add("$brand→$got")
            }
            if (split != null) {
                changed++
                bad.add("$brand split→$split")
            }
        }
        val bytes = common.sumOf { it.length * 2 }
        println("AUTOCORRECT_GUARD n=${common.size}+${brands.size} changed=$changed memory_chars=$bytes $bad")
        assertEquals(0, changed, bad.toString())
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

        /** Common English words. A word in the dictionary must not be rewritten into a different word. */
        private val COMMON_WORDS = """
            the of and to in is it you that he was for on are as with his they at be this have from
            or one had by word but not what all were we when your can said there use an each which
            she do how their if will up other about out many then them these so some her would make
            like him into time has look two more write go see number no way could people my than
            first water been call who oil its now find long down day did get come made may part
            over new sound take only little work know place year live me back give most very after
            thing our just name good sentence man think say great where help through much before
            line right too mean old any same tell boy follow came want show also around form three
            small set put end does another well large must big even such because turn here why ask
            went men read need land different home us move try kind hand picture again change off
            play spell air away animal house point page letter mother answer found study still learn
            should america world high every near add food between own below country plant last school
            father keep tree never start city earth eye light thought head under story saw left don
            few while along might close something seem next hard open example begin life always
            those both paper together got group often run important until children side feet car
            mile night walk white sea began grow took river four carry state once book hear stop
            without second later miss idea enough eat face watch far indian real almost let above
            girl sometimes mountain cut young talk soon list song being leave family body music
            color stand sun fish area mark dog horse birds room problem complete since ever piece
            told usually didn friends easy heard order red door sure become top ship across today
            during short better best however low hours black products happened whole measure remember
            early waves reached listen wind rock space covered fast several hold himself toward five
            step morning passed true hundred against pattern numeral table north slowly money map
            farm pulled draw voice seen cold cried plan notice south sing war ground fall king town
            unit figure certain field travel wood fire upon english road half ten fly gave box
            finally wait correct oh quickly person became shown minutes strong verb stars front feel
            fact inches street decided contain course surface produce building ocean class note
            nothing rest carefully scientists inside wheels stay green known island week less machine
            base ago stood plane system behind ran round boat game force brought understand warm
            common bring explain dry though language shape deep thousands yes clear equation yet
            government filled heat full hot check object am rule among noun power cannot able six
            size dark ball material special heavy fine pair circle include built can not
            done nothing else someone everyone anyone something nothing myself yourself himself
            herself itself ourselves themselves today tomorrow yesterday morning evening night
            monday tuesday wednesday thursday friday saturday sunday january february march april
            june july august september october november december spring summer autumn winter
            coffee water bread rice milk sugar salt pepper butter cheese meat fruit apple orange
            banana grape lemon bread egg chicken fish beef pork soup salad rice pasta pizza
            phone computer screen keyboard mouse window door table chair desk lamp clock watch
            shirt shoes coat hat bag book pen paper money bank shop store market street road
            train bus plane ship bike car truck taxi hotel room ticket passport visa airport
            doctor nurse hospital school teacher student class lesson question answer problem
            friend family mother father sister brother son daughter husband wife baby child
            happy sad angry tired hungry thirsty cold hot warm cool big small long short
            early late fast slow new old young good bad right wrong true false yes no
            please thanks sorry hello goodbye welcome okay sure maybe already still again
            always never often sometimes usually really very just only also even though
            because while where when what which who whose how why
            london paris tokyo berlin rome madrid lisbon dublin vienna prague warsaw athens
            cairo delhi mumbai bangkok singapore sydney melbourne auckland toronto chicago
            boston seattle denver miami austin dallas houston phoenix atlanta seattle
            gonna lol omw idk brb yeet zen
        """.trimIndent()
    }
}
