// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.suggestions.SuggestedWordInfo

/**
 * iPhone-style text replacement ("omw" -> "On my way!").
 *
 * HeliBoard's personal-dictionary shortcut is a different feature: it stores one dictionary
 * word plus an optional abbreviation, and that abbreviation is only a suggestion. It cannot
 * hold a phrase with spaces. These rules are the phrase expansion.
 *
 * The shortcut is one token with no spaces. The phrase may contain spaces and punctuation.
 * Matching is exact first, then a single case-insensitive match, so auto-capitalization
 * ("Omw" at the start of a sentence) still expands a lowercase shortcut.
 * Nothing here is logged or sent off the device.
 */
data class TextReplacement(val shortcut: String, val phrase: String)

object TextReplacementRules {
    const val MAX_SHORTCUT = 40
    const val MAX_PHRASE = 500

    /** Password fields and fields that forbid personalized learning never expand. */
    fun allowedInField(passwordField: Boolean, noPersonalizedLearning: Boolean): Boolean =
        !passwordField && !noPersonalizedLearning

    fun validate(shortcut: String, phrase: String): String? {
        val s = shortcut.trim()
        val p = phrase.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() } || s.length > MAX_SHORTCUT) return "shortcut"
        if (p.isEmpty() || p.length > MAX_PHRASE) return "phrase"
        if (s == p) return "same"
        return null
    }

    fun lookup(entries: List<TextReplacement>, typed: String): String? {
        if (typed.isEmpty() || entries.isEmpty()) return null
        entries.firstOrNull { it.shortcut == typed }?.let { return it.phrase }
        val folded = entries.filter { it.shortcut.equals(typed, ignoreCase = true) }
        return if (folded.size == 1) folded[0].phrase else null
    }

    /** Puts [phrase] in the slot that space commits (index 1, just after the typed word). */
    fun pinSuggestion(words: MutableList<SuggestedWordInfo>, phrase: String) {
        val existing = words.indexOfFirst {
            it.word == phrase && it.sourceDict === Dictionary.DICTIONARY_USER_SHORTCUT
        }
        val info = if (existing >= 0) words.removeAt(existing) else SuggestedWordInfo(
            phrase, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_WHITELIST,
            Dictionary.DICTIONARY_USER_SHORTCUT, SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
        )
        val at = if (words.isNotEmpty() && words[0].isKindOf(SuggestedWordInfo.KIND_TYPED)) 1 else 0
        words.add(at.coerceAtMost(words.size), info)
    }

    fun encode(entries: List<TextReplacement>): String {
        val out = StringBuilder()
        out.append("v1\n")
        for (entry in entries) {
            out.append(escape(entry.shortcut)).append('\t').append(escape(entry.phrase)).append('\n')
        }
        return out.toString()
    }

    fun decode(raw: String): List<TextReplacement> {
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != "v1") throw IllegalArgumentException("bad header")
        val result = ArrayList<TextReplacement>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) throw IllegalArgumentException("bad line")
            val shortcut = unescape(line.substring(0, tab))
            val phrase = unescape(line.substring(tab + 1))
            if (validate(shortcut, phrase) != null) throw IllegalArgumentException("bad entry")
            result.add(TextReplacement(shortcut, phrase))
        }
        return result
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(value[i])
                i++
            }
        }
        return out.toString()
    }
}
