// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

/**
 * Decisions that do not need the dictionary: what must never be rewritten,
 * and when a suggestion is too weak to apply on its own.
 */
object AutocorrectPolicy {
    /**
     * A word the user (or a text replacement) owns is not a typo.
     * Learned history and words restored with backspace are ignored in password,
     * incognito, and no-personalized-learning fields. The user dictionary and
     * text-replacement shortcuts are still left alone there, so a shortcut is
     * not rewritten into a different word.
     */
    fun keepTypedWord(
        inUserDictionary: Boolean,
        inLearnedHistory: Boolean,
        acceptedAfterRevert: Boolean,
        isTextReplacement: Boolean,
        blocksPersonalization: Boolean,
    ): Boolean {
        if (isTextReplacement || inUserDictionary) return true
        if (blocksPersonalization) return false
        return inLearnedHistory || acceptedAfterRevert
    }

    /** URLs, emails, numbers, and dotted tokens are typed on purpose. */
    fun isLiteralToken(word: String): Boolean {
        if (word.isEmpty()) return false
        for (ch in word) {
            if (ch.isDigit() || ch == '@' || ch == '.' || ch == '/' || ch == ':' || ch == '%' || ch == '+' || ch == '?' || ch == '&' || ch == '=') {
                return true
            }
        }
        return false
    }

    /**
     * @param typedIsKnown the typed word is already in a dictionary
     * @param forced whitelist or shortcut the user (or a dictionary) asked to expand
     * @return true when this suggestion must not replace the typed word
     */
    fun suppressCorrection(typed: String, candidate: String, typedIsKnown: Boolean, forced: Boolean): Boolean {
        if (isLiteralToken(typed)) return true
        if (forced) return false
        if (isCaseApostropheOrAccentOnly(typed, candidate)) return false
        // A real word is not "fixed" into a different real word. Context can still
        // offer the other word in the strip; it is not committed on space.
        if (typedIsKnown) return true
        if (lettersOf(typed).length <= 4 && !ProximityAutocorrect.isPlausibleCorrection(typed, candidate)) return true
        return false
    }

    fun isCaseApostropheOrAccentOnly(typed: String, candidate: String): Boolean {
        return fold(typed) == fold(candidate)
    }

    private fun fold(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            if (ch == '\'' || ch == '’') continue
            if (!ch.isLetter()) continue
            sb.append(KeyGeometry.baseLetter(ch))
        }
        return sb.toString()
    }

    fun lettersOf(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            if (ch.isLetter()) sb.append(KeyGeometry.baseLetter(ch))
        }
        return sb.toString()
    }
}
