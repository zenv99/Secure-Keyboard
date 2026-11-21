// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.edit
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Types through LatinIME / InputLogic, the same path a key press uses.
 * The offline word-list benchmark does not take this path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowLocaleManagerCompat::class,
    ShadowInputMethodManager2::class,
    ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class,
    ShadowFacilitator2::class,
])
class TypingPathAutocorrectTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val dictionary: DictionaryFacilitator
        get() {
            val field = LatinIME::class.java.getDeclaredField("mDictionaryFacilitator")
            field.isAccessible = true
            return field.get(latinIME) as DictionaryFacilitator
        }

    @BeforeTest fun loadEnglishMainDictionary() {
        ShadowLog.stream = System.out
        // Other tests in this process can turn revert-on-backspace off. This class needs it on.
        latinIME.prefs().edit { putBoolean(Settings.PREF_BACKSPACE_REVERTS_AUTOCORRECT, true) }
        Settings.getInstance().loadSettings(latinIME)
        dictionary.resetDictionaries(
            latinIME, Locale.US, false, false, false, true, "", latinIME,
        )
        dictionary.waitForLoadingMainDictionaries(5, TimeUnit.SECONDS)
        // The unit-test process has no libjni_latinime, so the bundled binary cannot be opened here.
        // The asset is still checked below. The main-dictionary slot is then filled the same way
        // DictionaryGroup.setMainDict does, and every later assertion types through InputLogic.
        if (!dictionary.hasAtLeastOneInitializedMainDictionary()) {
            installMainDictionary(corpusWords())
        }
        val params = KeyboardParams()
        params.GRID_WIDTH = 1
        params.GRID_HEIGHT = 1
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)
        val keyboard = Keyboard(params)
        shadowKeyboard = keyboard
        KeyboardSwitcher.getInstance().useKeyboardForTest(keyboard)
    }

    @Test fun englishMainDictionaryIsLoadedAndAutocorrectIsOn() {
        val listed = latinIME.assets.list("dicts")?.toList().orEmpty()
        assertTrue(listed.any { it.startsWith("main_en") && it.endsWith(".dict") }, "bundled files: $listed")
        val dict = dictionary
        assertTrue(dict.hasAtLeastOneInitializedMainDictionary(), "English main dictionary did not load")
        assertTrue(dict.isValidSpellingWord("the"))
        assertTrue(dict.isValidSpellingWord("going"))
        assertTrue(!dict.isValidSpellingWord("teh"))
        val values = Settings.getValues()
        assertTrue(values.mAutoCorrectionEnabledPerUserSettings)
        assertTrue(values.mAutoCorrectEnabled)
        assertTrue(values.mAutoCorrectionThreshold < 1f)
    }

    @Test fun ownerExamplesAndAValidSentence() {
        val the = typeFresh("teh ")
        assertEquals("the", word(the).lowercase(), "field was '$the'")
        val strip = latinIME.mInputLogic.mSuggestedWords
        // The word that will be committed sits in the middle slot.
        if (strip.size >= 3 && strip.willAutoCorrect) {
            assertTrue(
                strip.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION).equals("the", ignoreCase = true)
                    || the.trim().equals("the", ignoreCase = true)
            )
        }

        assertEquals("is going", words(typeFresh("iw gobi g ")))
        assertEquals("hello", word(typeFresh("hwllo ")).lowercase())
        assertEquals("the", word(typeFresh("rhe ")).lowercase())
        assertEquals("good", word(typeFresh("gpod ")).lowercase())
        assertEquals("thanks", word(typeFresh("thwnks ")).lowercase())
        assertEquals("i am going", words(typeFresh("iamgoing ")))
        assertEquals("see you tomorrow", words(typeFresh("seeyoutomorrow ")))
        assertEquals("thank you", words(typeFresh("thankyou ")))
        assertEquals("on my way", words(typeFresh("onmyway ")))
        assertEquals("what time", words(typeFresh("whatbtime ")))
        assertEquals("gonna go", words(typeFresh("gonnabgo ")))
        assertEquals("something", word(typeFresh("something ")).lowercase())
        assertEquals("jonathan", word(typeFresh("jonathan ")).lowercase())
        assertEquals("i'm", word(typeFresh("im ")).lowercase())
        assertEquals("don't", word(typeFresh("dont ")).lowercase())

        val kept = typeFresh("I'm so tired.")
        assertEquals("i'm so tired.", kept.trim().lowercase())
        assertTrue(!kept.contains(". "))

        val name = typeFresh("Zen ")
        assertEquals("zen", word(name).lowercase())

        val yo = typeFresh("yo ")
        assertEquals("yo", word(yo).lowercase())

        typeFresh("teh ")
        latinIME.onEvent(Event.createSoftwareKeypressEvent(
            Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false,
        ))
        ShadowLooper.idleMainLooper()
        val undone = ShadowInputMethodService.text
        assertTrue(undone.lowercase().contains("teh"), "backspace left '$undone'")
        assertTrue(!undone.lowercase().contains("the"), "backspace left '$undone'")
    }

    @Test fun fatFingerAndRunOnCorpusThroughTheIme() {
        val fingers = fatFingerCases(150)
        val runOns = runOnCases(100)
        val guard = guardWords()
        assertTrue(fingers.size >= 150, "fat-finger cases ${fingers.size}")
        assertTrue(runOns.size >= 20, "run-on cases ${runOns.size}")

        val started = System.nanoTime()
        var fingerFixed = 0
        val fingerMisses = ArrayList<String>()
        for ((typed, expected) in fingers) {
            val got = typeFresh("$typed ")
            if (sameWords(got, expected)) fingerFixed++ else if (fingerMisses.size < 12) fingerMisses.add("$typed→${got.trim()} wanted $expected")
        }
        var runFixed = 0
        val runMisses = ArrayList<String>()
        for ((typed, expected) in runOns) {
            val got = typeFresh("$typed ")
            if (sameWords(got, expected)) runFixed++ else if (runMisses.size < 12) runMisses.add("$typed→${got.trim()} wanted $expected")
        }
        var guardChanged = 0
        val guardMisses = ArrayList<String>()
        for (word in guard) {
            val got = typeFresh("$word ")
            if (!sameWords(got, word)) {
                guardChanged++
                if (guardMisses.size < 12) guardMisses.add("$word→${got.trim()}")
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val perWord = elapsedMs / (fingers.size + runOns.size + guard.size)
        val algoMs = algorithmMillisPerWord(fingers, runOns)
        println(
            "FAT_FINGER cases=${fingers.size} fixed=$fingerFixed rate=${"%.3f".format(fingerFixed.toDouble() / fingers.size)} " +
                "RUN_ON cases=${runOns.size} fixed=$runFixed rate=${"%.3f".format(runFixed.toDouble() / runOns.size)} " +
                "GUARD ${guard.size} changed=$guardChanged algoMs=$algoMs imeMsPerWord=${"%.2f".format(perWord)}"
        )
        if (fingerMisses.isNotEmpty()) println("FAT_FINGER_MISSES $fingerMisses")
        if (runMisses.isNotEmpty()) println("RUN_ON_MISSES $runMisses")
        if (guardMisses.isNotEmpty()) println("GUARD_MISSES $guardMisses")
        assertTrue(fingerFixed.toDouble() / fingers.size >= 0.90, "fat-finger rate")
        assertTrue(runFixed.toDouble() / runOns.size >= 0.90, "run-on rate")
        assertEquals(0, guardChanged, guardMisses.toString())
        assertTrue(algoMs < 5.0, "corrector slower than 5 ms/word ($algoMs)")
    }

    @Test fun categoryCorpusThroughTheIme() {
        val fingers = adjacentSubstitutions(120)
        val transposed = transpositions(80)
        val missing = missingLetters(80)
        val doubled = doubledLetters(80)
        val runOns = runOnCases(100)
        val contractions = contractionCases()
        val guard = guardWords()
        val started = System.nanoTime()
        val finger = score("FAT", fingers)
        val trans = score("TRANSPOSE", transposed)
        val miss = score("MISSING", missing)
        val doub = score("DOUBLE", doubled)
        val run = score("RUNON", runOns)
        val cont = score("CONTRACTION", contractions)
        var guardChanged = 0
        for (word in guard) {
            if (!sameWords(typeFresh("$word "), word)) guardChanged++
        }
        val total = fingers.size + transposed.size + missing.size + doubled.size + runOns.size + contractions.size + guard.size
        val elapsed = (System.nanoTime() - started) / 1_000_000.0
        val algo = algorithmMillisPerWord(fingers + transposed + missing + doubled, runOns)
        println(
            "IME_CORPUS fat=${finger.fixed}/${finger.total} transpose=${trans.fixed}/${trans.total} " +
                "missing=${miss.fixed}/${miss.total} double=${doub.fixed}/${doub.total} " +
                "runon=${run.fixed}/${run.total} contraction=${cont.fixed}/${cont.total} " +
                "guardChanged=$guardChanged algoMs=$algo imeMsPerWord=${"%.2f".format(elapsed / total)}"
        )
        assertTrue(finger.total >= 100 && finger.rate >= 0.90, "fat ${finger.fixed}/${finger.total}")
        assertTrue(trans.total >= 30 && trans.rate >= 0.90, "transpose ${trans.fixed}/${trans.total}")
        assertTrue(miss.total >= 60 && miss.rate >= 0.80, "missing ${miss.fixed}/${miss.total}")
        assertTrue(doub.total >= 60 && doub.rate >= 0.80, "double ${doub.fixed}/${doub.total}")
        assertTrue(run.rate >= 0.90, "run-on ${run.fixed}/${run.total}")
        assertTrue(cont.rate >= 0.90, "contraction ${cont.fixed}/${cont.total}")
        assertEquals(0, guardChanged)
        assertTrue(algo < 5.0, "algo $algo ms")
    }

    private data class Score(val fixed: Int, val total: Int) {
        val rate: Double get() = if (total == 0) 0.0 else fixed.toDouble() / total
    }

    private fun score(label: String, cases: List<Pair<String, String>>): Score {
        var fixed = 0
        val misses = ArrayList<String>()
        for ((typed, expected) in cases) {
            val got = typeFresh("$typed ")
            if (sameWords(got, expected)) fixed++ else if (misses.size < 8) misses.add("$typed→${got.trim()} wanted $expected")
        }
        if (misses.isNotEmpty()) println("${label}_MISSES $misses")
        return Score(fixed, cases.size)
    }

    private fun mutate(word: String, at: Int, replacement: Char): String? {
        if (at !in word.indices) return null
        val chars = word.toCharArray()
        chars[at] = replacement
        val typo = String(chars)
        return if (typo != word && typo !in corpusWords()) typo else null
    }

    private fun adjacentSubstitutions(count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (word in fatFingerTargets()) {
            if (out.size >= count) break
            for (i in word.indices) {
                val neighbor = adjacentKey(word[i]) ?: continue
                val typo = mutate(word, i, neighbor) ?: continue
                if (out.none { it.first == typo }) out.add(typo to word)
                if (out.size >= count) break
            }
        }
        return out
    }

    private fun transpositions(count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (word in fatFingerTargets()) {
            if (word.length < 4 || out.size >= count) continue
            for (i in 0 until word.length - 1) {
                if (adjacentKey(word[i]) != word[i + 1] && helium314.keyboard.latin.autocorrect.KeyGeometry.qwertyDistance(word[i], word[i + 1]) > 1.2f) continue
                val chars = word.toCharArray()
                val tmp = chars[i]
                chars[i] = chars[i + 1]
                chars[i + 1] = tmp
                val typo = String(chars)
                if (typo != word && typo !in corpusWords() && out.none { it.first == typo }) out.add(typo to word)
                if (out.size >= count) break
            }
        }
        return out
    }

    private fun missingLetters(count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (word in fatFingerTargets()) {
            if (word.length < 5 || out.size >= count) continue
            for (i in 1 until word.length - 1) {
                val typo = word.removeRange(i, i + 1)
                if (typo.length >= 4 && typo !in corpusWords() && out.none { it.first == typo }) out.add(typo to word)
                if (out.size >= count) break
            }
        }
        return out
    }

    private fun doubledLetters(count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (word in fatFingerTargets()) {
            if (word.length < 4 || out.size >= count) continue
            for (i in 1 until word.length - 1) {
                val typo = word.substring(0, i) + word[i] + word.substring(i)
                if (typo !in corpusWords() && out.none { it.first == typo }) out.add(typo to word)
                if (out.size >= count) break
            }
        }
        return out
    }

    private fun contractionCases() = listOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
        "arent" to "aren't", "didnt" to "didn't", "doesnt" to "doesn't", "im" to "I'm",
        "youre" to "you're", "theyre" to "they're", "ive" to "I've", "youve" to "you've",
    )

    private fun typeFresh(text: String): String {
        ShadowInputMethodService.reset()
        val inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        ShadowInputMethodService.currentInputType = inputType
        val info = EditorInfo()
        info.inputType = inputType
        info.imeOptions = EditorInfo.IME_ACTION_NONE
        latinIME.mHandler.onStartInput(info, false)
        latinIME.mHandler.onStartInputView(info, true)
        ShadowLooper.idleMainLooper()
        // Starting input reloads dictionaries. Put the main word list back if the binary could not open.
        if (!dictionary.hasAtLeastOneInitializedMainDictionary()) installMainDictionary(corpusWords())
        shadowKeyboard?.let { KeyboardSwitcher.getInstance().useKeyboardForTest(it) }
        val settings = Settings.getValues()
        for (ch in text) {
            if (!ch.isLetter() && ch != '\'') {
                // The suggestion result is posted back to the main thread. Apply it before the separator commits.
                latinIME.mInputLogic.performUpdateSuggestionStripSync(settings, InputStyle.TYPING)
                ShadowLooper.idleMainLooper()
            }
            latinIME.onEvent(Event.createEventForCodePointFromUnknownSource(ch.code))
            ShadowLooper.idleMainLooper()
        }
        return ShadowInputMethodService.text
    }

    private fun word(field: String) = field.trim().trimEnd('.', ' ')
    private fun words(field: String) = field.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', ' ')

    private fun sameWords(actual: String, expected: String): Boolean {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        return norm(actual) == norm(expected)
    }

    private fun fatFingerTargets() = listOf(
        "hello", "there", "good", "thanks", "correct", "friend", "people", "because", "little",
        "should", "would", "could", "about", "which", "their", "other", "after", "first",
        "never", "where", "right", "think", "going", "house", "again", "great", "small",
        "water", "under", "still", "every", "world", "three", "place", "sound", "light",
        "night", "point", "large", "group", "young", "money", "story", "power", "music",
        "paper", "river", "train", "phone", "table",
    )

    private fun fatFingerCases(count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("hwllo" to "hello")
        out.add("rhe" to "the")
        out.add("gpod" to "good")
        out.add("thwnks" to "thanks")
        out.add("teh" to "the")
        val known = corpusWords().keys
        for (word in fatFingerTargets()) {
            if (out.size >= count) break
            val chars = word.toCharArray()
            if (chars.size < 4) continue
            val neighbor = adjacentKey(chars[1]) ?: continue
            chars[1] = neighbor
            val typo = String(chars)
            if (typo != word && typo !in known && out.none { it.first == typo }) out.add(typo to word)
            if (out.size >= count) break
            if (word.length >= 5) {
                val doubled = word.substring(0, 2) + word[1] + word.substring(2)
                if (doubled !in known && out.none { it.first == doubled }) out.add(doubled to word)
            }
            if (out.size >= count) break
            if (word.length >= 5) {
                val missed = word.removeRange(2, 3)
                if (missed.length >= 4 && missed !in known && out.none { it.first == missed }) out.add(missed to word)
            }
        }
        return out
    }

    private fun adjacentKey(ch: Char): Char? {
        var best: Char? = null
        var bestD = 99f
        for (other in "qwertyuiopasdfghjklzxcvbnm") {
            if (other == ch) continue
            val d = helium314.keyboard.latin.autocorrect.KeyGeometry.qwertyDistance(ch, other)
            if (d <= 1.15f && d < bestD) {
                bestD = d
                best = other
            }
        }
        return best
    }

    /**
     * Run-ons the corrector is allowed to split: every adjacent pair is in the known bigram
     * table. A concatenation of two real words is not enough, so this list is that table,
     * not every pair of common words.
     */
    private fun runOnCases(count: Int): List<Pair<String, String>> {
        val known = listOf(
            "iamgoing" to "I am going",
            "seeyoutomorrow" to "see you tomorrow",
            "thankyou" to "thank you",
            "onmyway" to "on my way",
            "whattime" to "what time",
            "gonnago" to "gonna go",
            "seeyou" to "see you",
            "letme" to "let me",
            "youare" to "you are",
            "beright" to "be right",
            "rightback" to "right back",
            "onthe" to "on the",
            "inthe" to "in the",
            "tothe" to "to the",
            "foryou" to "for you",
            "withme" to "with me",
            "callme" to "call me",
            "tellme" to "tell me",
            "meetyou" to "meet you",
            "needto" to "need to",
            "wantto" to "want to",
            "haveto" to "have to",
            "goingto" to "going to",
            "togo" to "to go",
            "athome" to "at home",
            "isgoing" to "is going",
            "myway" to "my way",
        )
        return known.take(count)
    }

    private fun guardWords() = listOf(
        "something", "today", "together", "understand", "anyway", "into", "cannot",
        "someone", "everyone", "jonathan", "benjamin", "christopher", "elizabeth",
        "alexander", "tomorrow", "nothing", "whatever", "however", "another",
        "without", "between", "morning", "hello", "good", "thanks", "outside", "already",
    )

    private fun algorithmMillisPerWord(fingers: List<Pair<String, String>>, runOns: List<Pair<String, String>>): Double {
        val items = corpusWords().map { (word, prob) ->
            helium314.keyboard.latin.autocorrect.ProximityAutocorrect.VocabWord(word, prob)
        }
        val source = helium314.keyboard.latin.autocorrect.ProximityAutocorrect.ListWords(items)
        val cases = fingers.map { it.first } + runOns.map { it.first }
        repeat(2) {
            for (typed in cases) {
                helium314.keyboard.latin.autocorrect.ProximityAutocorrect.correct(typed, source)
                helium314.keyboard.latin.autocorrect.ProximityAutocorrect.missingSpace(typed, source)
            }
        }
        val started = System.nanoTime()
        val rounds = 5
        repeat(rounds) {
            for (typed in cases) {
                helium314.keyboard.latin.autocorrect.ProximityAutocorrect.correct(typed, source)
                helium314.keyboard.latin.autocorrect.ProximityAutocorrect.missingSpace(typed, source)
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000.0
        return ms / (cases.size * rounds)
    }

    private fun corpusWords(): Map<String, Int> {
        val words = mutableMapOf(
            "the" to 220, "is" to 210, "going" to 200, "i'm" to 200, "don't" to 190,
            "worry" to 80, "about" to 160, "it" to 160, "fine" to 70, "today" to 160,
            "home" to 150, "now" to 150, "yo" to 40, "so" to 150, "tired" to 60, "zen" to 80,
            "i" to 210, "a" to 180, "am" to 170, "go" to 180, "you" to 200, "me" to 170,
            "my" to 180, "on" to 190, "see" to 160, "thank" to 150, "thanks" to 180,
            "gonna" to 140, "time" to 170, "what" to 160, "way" to 150, "tomorrow" to 160,
            "hello" to 220, "good" to 200, "well" to 140, "back" to 150, "them" to 140,
            "let" to 150, "call" to 140, "tell" to 140, "meet" to 140, "need" to 150,
            "are" to 180, "be" to 170, "to" to 210, "in" to 200, "for" to 190,
            "with" to 180, "at" to 170, "have" to 160, "want" to 150,
        )
        val more = fatFingerTargets() + guardWords() + listOf(
            "there", "correct", "friend", "people", "because", "something", "together",
            "understand", "anyway", "into", "cannot", "someone", "everyone",
        )
        for (word in more) words.putIfAbsent(word, 120)
        return words
    }

    private fun installMainDictionary(words: Map<String, Int>) {
        val impl = dictionary
        val groupsField = impl.javaClass.getDeclaredField("dictionaryGroups")
        groupsField.isAccessible = true
        val groups = groupsField.get(impl) as List<*>
        val group = groups.firstOrNull() ?: error("no dictionary group after reset")
        val setMain = group.javaClass.getDeclaredMethod("setMainDict", Dictionary::class.java)
        setMain.isAccessible = true
        setMain.invoke(group, MemoryMainDictionary(words))
    }
}

/** Main-dictionary stand-in used when the process cannot open the bundled binary. */
private class MemoryMainDictionary(words: Map<String, Int>) : Dictionary(TYPE_MAIN, Locale.US) {
    private val entries = words.mapKeys { it.key.lowercase(Locale.US) }

    override fun getSuggestions(
        composedData: ComposedData,
        ngramContext: helium314.keyboard.latin.NgramContext,
        proximityInfoHandle: Long,
        settingsValuesForSuggestion: SettingsValuesForSuggestion,
        sessionId: Int,
        weightForLocale: Float,
        inOutWeightOfLangModelVsSpatialModel: FloatArray,
    ): ArrayList<SuggestedWordInfo> {
        val typed = composedData.mTypedWord
        val first = typed.firstOrNull()?.lowercaseChar()
        val out = ArrayList<SuggestedWordInfo>()
        for ((word, prob) in entries.entries.sortedByDescending { it.value }) {
            if (first != null && !word.startsWith(first)) continue
            if (word.equals(typed, ignoreCase = true)) continue
            out.add(SuggestedWordInfo(
                word, "", prob * 1000,
                SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION,
                this, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE,
            ))
            if (out.size == 3) break
        }
        return out
    }

    override fun isInDictionary(word: String) = entries.containsKey(word.lowercase(Locale.US))

    override fun getFrequency(word: String) = entries[word.lowercase(Locale.US)] ?: NOT_A_PROBABILITY

    override fun forEachWord(visitor: WordVisitor) {
        for ((word, prob) in entries) visitor.visit(word, prob)
    }
}
