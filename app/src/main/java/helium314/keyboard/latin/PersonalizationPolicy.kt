// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

/**
 * When this is true, the keyboard must not learn from the field, must not suggest from
 * history or other personalized dictionaries, and must not store what was typed.
 * Password fields and [android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]
 * are included, as is the user's always-incognito setting.
 */
object PersonalizationPolicy {
    @JvmStatic
    fun blocksPersonalization(
        isPasswordField: Boolean,
        noPersonalizedLearning: Boolean,
        alwaysIncognito: Boolean,
    ): Boolean = isPasswordField || noPersonalizedLearning || alwaysIncognito
}
