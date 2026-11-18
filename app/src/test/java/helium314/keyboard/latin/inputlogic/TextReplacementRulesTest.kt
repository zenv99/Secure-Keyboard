// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun unknownShortcutDoesNotExpand() {
        assertNull(TextReplacementRules.lookup(entries, "hello"))
        assertNull(TextReplacementRules.lookup(emptyList(), "omw"))
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
}
