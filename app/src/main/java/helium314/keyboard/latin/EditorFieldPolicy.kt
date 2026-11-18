// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.InputTypeUtils

/**
 * Which fields get dictionary correction, and which may be learned from.
 *
 * Chromium reports a page search box as [InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT] and, when
 * the page sets autocomplete off, also [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS]. It does not set
 * [InputType.TYPE_TEXT_FLAG_AUTO_CORRECT] for `input type=search`. The DuckDuckGo address/search
 * bar is `textUri|textNoSuggestions` with action Go and [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING].
 * Those flags used to turn correction off entirely.
 *
 * A bare URI field (HTML `type=url`, no no-suggestions flag, action other than Search) stays
 * uncorrected. Email and password fields stay uncorrected. No-learning and password fields are
 * not stored; the main dictionary may still correct a field that is otherwise eligible.
 */
object EditorFieldPolicy {
    data class Decision(
        val showSuggestions: Boolean,
        val autoCorrect: Boolean,
        val blockLearning: Boolean,
    )

    @JvmStatic
    fun decide(inputType: Int, imeOptions: Int): Decision {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val autoCorrectFlag = inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT != 0
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val password = InputTypeUtils.isAnyPasswordInputType(inputType)
        if (inputClass != InputType.TYPE_CLASS_TEXT || password) {
            return Decision(showSuggestions = false, autoCorrect = false, blockLearning = password || noLearning)
        }
        if (InputTypeUtils.isEmailVariation(variation)) {
            return Decision(showSuggestions = false, autoCorrect = false, blockLearning = noLearning)
        }
        if (variation == InputType.TYPE_TEXT_VARIATION_URI && !isSearchReportedAsUri(action, noSuggestions, autoCorrectFlag)) {
            return Decision(showSuggestions = false, autoCorrect = false, blockLearning = noLearning)
        }
        return Decision(showSuggestions = true, autoCorrect = true, blockLearning = noLearning)
    }

    /**
     * Chromium `type=search` is web-edit text. Some browsers, including DuckDuckGo's search bar,
     * report the same box as a URI with no-suggestions. A URI field without that flag and without
     * a search action is a dedicated URL field.
     */
    private fun isSearchReportedAsUri(action: Int, noSuggestions: Boolean, autoCorrectFlag: Boolean): Boolean {
        return action == EditorInfo.IME_ACTION_SEARCH || noSuggestions || autoCorrectFlag
    }
}
