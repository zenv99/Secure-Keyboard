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
 * A URI field, an email field, a password field, and any field with
 * TYPE_TEXT_FLAG_NO_SUGGESTIONS stay uncorrected, matching AOSP InputAttributes.
 * No-learning and password fields are not stored.
 */
object EditorFieldPolicy {
    data class Decision(
        val showSuggestions: Boolean,
        val autoCorrect: Boolean,
        val blockLearning: Boolean,
    )

    /**
     * Double-space period is for prose. A URL, email, or search field treats a space as a space.
     * A normal web text box still gets the shortcut. A search action does not, including a
     * search box reported as a URI.
     */
    @JvmStatic
    fun allowsDoubleSpacePeriod(inputType: Int, imeOptions: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (InputTypeUtils.isEmailVariation(variation)) return false
        if (variation == InputType.TYPE_TEXT_VARIATION_URI) return false
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        return action != EditorInfo.IME_ACTION_SEARCH
    }

    @JvmStatic
    fun decide(inputType: Int, imeOptions: Int): Decision {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val noSuggestions = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val password = InputTypeUtils.isAnyPasswordInputType(inputType)
        if (inputClass != InputType.TYPE_CLASS_TEXT || password) {
            return Decision(showSuggestions = false, autoCorrect = false, blockLearning = password || noLearning)
        }
        if (InputTypeUtils.isEmailVariation(variation)) {
            return Decision(showSuggestions = false, autoCorrect = false, blockLearning = noLearning)
        }
        // AOSP InputAttributes: a URL field and TYPE_TEXT_FLAG_NO_SUGGESTIONS do not auto-correct.
        if (variation == InputType.TYPE_TEXT_VARIATION_URI || noSuggestions) {
            return Decision(showSuggestions = true, autoCorrect = false, blockLearning = noLearning)
        }
        return Decision(showSuggestions = true, autoCorrect = true, blockLearning = noLearning)
    }
}
