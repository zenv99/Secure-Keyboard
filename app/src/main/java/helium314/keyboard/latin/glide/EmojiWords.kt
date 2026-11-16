// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import helium314.keyboard.latin.App
import java.util.Locale

/** Bundled word -> emoji list ("sun" -> sun emoji), so emoji suggestions work with no extra download. */
object EmojiWords {
    @Volatile
    private var words: Map<String, List<String>>? = null

    fun forWord(word: String): List<String> {
        val map = words ?: load()
        return map[word.lowercase(Locale.ROOT)] ?: emptyList()
    }

    @Synchronized
    private fun load(): Map<String, List<String>> {
        words?.let { return it }
        val map = HashMap<String, List<String>>()
        try {
            val assets = App.app?.assets
            if (assets != null) {
                assets.open("emoji_words.tsv").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab <= 0) continue
                        map[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                    }
                }
            }
        } catch (_: Exception) {
            // no emoji words: suggestions just stay as they are
        }
        words = map
        return map
    }
}
