// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextReplacementRulesTest {
    private val entries = listOf(
        TextReplacement("omw", "On my way!"),
        TextReplacement("addr", "12 King St"),
        TextReplacement("Sig", "Sent from my phone"),
    )

    @Test fun exactShortcutExpandsToThePhrase() {
        assertEquals("On my way!", TextReplacementRules.lookup(entries, "omw"))
        assertEquals("12 King St", TextReplacementRules.lookup(entries, "addr"))
    }

    @Test fun autoCapitalStillExpandsTheOnlyCaseInsensitiveMatch() {
        assertEquals("On my way!", TextReplacementRules.lookup(entries, "Omw"))
        assertEquals("On my way!", TextReplacementRules.lookup(entries, "OMW"))
    }

    @Test fun exactCaseWinsOverAFoldedMatch() {
        val both = entries + TextReplacement("OMW", "ON MY WAY")
        assertEquals("ON MY WAY", TextReplacementRules.lookup(both, "OMW"))
        assertEquals("On my way!", TextReplacementRules.lookup(both, "omw"))
    }

    @Test fun twoCaseInsensitiveMatchesDoNotGuess() {
        val both = listOf(
            TextReplacement("omw", "On my way!"),
            TextReplacement("OMW", "ON MY WAY"),
        )
        assertNull(TextReplacementRules.lookup(both, "Omw"))
    }

    @Test fun unknownShortcutDoesNotExpand() {
        assertNull(TextReplacementRules.lookup(entries, "hello"))
        assertNull(TextReplacementRules.lookup(emptyList(), "omw"))
    }

    @Test fun passwordAndNoLearningFieldsDoNotExpand() {
        assertFalse(TextReplacementRules.allowedInField(passwordField = true, noPersonalizedLearning = false))
        assertFalse(TextReplacementRules.allowedInField(passwordField = false, noPersonalizedLearning = true))
        assertTrue(TextReplacementRules.allowedInField(passwordField = false, noPersonalizedLearning = false))
    }

    @Test fun shortcutCannotContainSpacesAndPhraseCan() {
        assertEquals("shortcut", TextReplacementRules.validate("on my", "way"))
        assertEquals("phrase", TextReplacementRules.validate("omw", "   "))
        assertEquals("same", TextReplacementRules.validate("omw", "omw"))
        assertNull(TextReplacementRules.validate("omw", "On my way!"))
    }

    @Test fun fileRoundTripKeepsSpacesNewlinesAndTabs() {
        val original = listOf(
            TextReplacement("omw", "On my way!"),
            TextReplacement("br", "line one\nline\ttwo"),
        )
        val decoded = TextReplacementRules.decode(TextReplacementRules.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun sealedFileDoesNotContainThePhrase() {
        val key = testKey()
        val plain = TextReplacementRules.encode(entries).toByteArray(Charsets.UTF_8)
        val sealed = TextReplacementCrypto.seal(plain, key)
        val asText = sealed.toString(Charsets.ISO_8859_1)
        assertFalse(asText.contains("On my way"))
        assertFalse(asText.contains("omw"))
        assertEquals(plain.toList(), TextReplacementCrypto.open(sealed, key).toList())
        val again = TextReplacementCrypto.seal(plain, key)
        assertNotEquals(sealed.toList(), again.toList())
    }

    @Test fun phraseIsTheSuggestionSpaceWillCommit() {
        val typed = SuggestedWordInfo(
            "omw", "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
        )
        val other = SuggestedWordInfo(
            "omg", "", 10, SuggestedWordInfo.KIND_CORRECTION,
            Dictionary.DICTIONARY_USER_TYPED, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
        )
        val words = mutableListOf(typed, other)
        TextReplacementRules.pinSuggestion(words, "On my way!")
        assertEquals("omw", words[0].word)
        assertEquals("On my way!", words[1].word)
        assertEquals(Dictionary.TYPE_USER_SHORTCUT, words[1].sourceDict.mDictType)
    }

    private fun testKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        return generator.generateKey()
    }
}
