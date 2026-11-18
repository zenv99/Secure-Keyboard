// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

/**
 * How a corrected word meets the separator the user just typed.
 * Tight punctuation stays glued to the word ("the."). A space stays a single space.
 */
object AutocorrectSpacing {
    private val tight = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '…')

    @JvmStatic
    fun wordBeforeSeparator(word: String, separator: String): String {
        if (separator.length == 1 && separator[0] in tight) return word.trimEnd()
        return word
    }

    @JvmStatic
    fun join(word: String, separator: String): String {
        val body = wordBeforeSeparator(word, separator)
        if (separator.isEmpty()) return body
        if (separator == " ") return if (body.endsWith(' ')) body else "$body "
        return body + separator
    }
}
