// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorFieldPolicyTest {
    @Test fun doubleSpacePeriodSkipsUrlEmailAndSearchButNotAWebComment() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(EditorFieldPolicy.allowsDoubleSpacePeriod(text, EditorInfo.IME_ACTION_NONE))
        val web = text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        assertTrue(EditorFieldPolicy.allowsDoubleSpacePeriod(web, EditorInfo.IME_ACTION_NONE))
        assertFalse(EditorFieldPolicy.allowsDoubleSpacePeriod(web, EditorInfo.IME_ACTION_SEARCH))
        assertFalse(EditorFieldPolicy.allowsDoubleSpacePeriod(
            text or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            EditorInfo.IME_ACTION_GO,
        ))
        assertFalse(EditorFieldPolicy.allowsDoubleSpacePeriod(
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NONE,
        ))
        assertFalse(EditorFieldPolicy.allowsDoubleSpacePeriod(
            text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NONE,
        ))
        assertFalse(EditorFieldPolicy.allowsDoubleSpacePeriod(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_NONE,
        ))
    }

    @Test fun webEditTextCorrects() {
        val web = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
        val plain = EditorFieldPolicy.decide(web, EditorInfo.IME_ACTION_NONE)
        assertTrue(plain.showSuggestions)
        assertTrue(plain.autoCorrect)
        assertFalse(plain.blockLearning)

        val multi = web or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val area = EditorFieldPolicy.decide(multi, EditorInfo.IME_ACTION_NONE)
        assertTrue(area.autoCorrect)
        assertTrue(area.showSuggestions)
    }

    @Test fun chromiumSearchBoxDoesNotAutocorrectWhenSuggestionsAreOff() {
        // AOSP: TYPE_TEXT_FLAG_NO_SUGGESTIONS does not auto-correct. The strip may still show.
        val type = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
        val decision = EditorFieldPolicy.decide(type, EditorInfo.IME_ACTION_SEARCH)
        assertTrue(decision.showSuggestions)
        assertFalse(decision.autoCorrect)
        assertFalse(decision.blockLearning)
    }

    @Test fun duckDuckGoSearchBarDoesNotAutocorrectAndDoesNotLearn() {
        // view_search_bar.xml: textUri|textNoSuggestions, actionGo|flagNoExtractUi|flagNoPersonalizedLearning
        val type = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val options = EditorInfo.IME_ACTION_GO or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val decision = EditorFieldPolicy.decide(type, options)
        assertTrue(decision.showSuggestions)
        assertFalse(decision.autoCorrect)
        assertTrue(decision.blockLearning)
    }

    @Test fun noSuggestionsFlagDoesNotAutocorrect() {
        val normal = InputType.TYPE_CLASS_TEXT
        assertTrue(EditorFieldPolicy.decide(normal, EditorInfo.IME_ACTION_DONE).autoCorrect)
        val noSuggestions = normal or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val decision = EditorFieldPolicy.decide(noSuggestions, EditorInfo.IME_ACTION_DONE)
        assertTrue(decision.showSuggestions)
        assertFalse(decision.autoCorrect)
        assertFalse(decision.blockLearning)
    }

    @Test fun incognitoWebTextCorrectsFromDictionaryWithoutLearning() {
        val web = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val decision = EditorFieldPolicy.decide(web, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(decision.showSuggestions)
        assertTrue(decision.autoCorrect)
        assertTrue(decision.blockLearning)
    }

    @Test fun passwordFieldsDoNotCorrectOrLearn() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )) {
            val decision = EditorFieldPolicy.decide(type, EditorInfo.IME_ACTION_DONE)
            assertFalse(decision.showSuggestions, type.toString())
            assertFalse(decision.autoCorrect, type.toString())
            assertTrue(decision.blockLearning, type.toString())
        }
    }

    @Test fun emailFieldsDoNotAutocorrect() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )) {
            val decision = EditorFieldPolicy.decide(type, EditorInfo.IME_ACTION_GO)
            assertFalse(decision.autoCorrect, type.toString())
            assertFalse(decision.showSuggestions, type.toString())
            assertFalse(decision.blockLearning, type.toString())
        }
    }

    @Test fun bareUrlFieldDoesNotAutocorrect() {
        // Chromium HTML type=url replaces the input type with URI and does not keep no-suggestions.
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val decision = EditorFieldPolicy.decide(type, EditorInfo.IME_ACTION_GO)
        assertFalse(decision.autoCorrect)
        assertTrue(decision.showSuggestions)
        assertFalse(decision.blockLearning)
    }

    @Test fun urlFieldDoesNotAutocorrectEvenAsSearch() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val decision = EditorFieldPolicy.decide(type, EditorInfo.IME_ACTION_SEARCH)
        assertFalse(decision.autoCorrect)
        assertTrue(decision.showSuggestions)
    }
}
