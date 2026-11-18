// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.glide

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class EmojiWordsTest {
    private val words = load()

    @Test fun sunIncludesFaceVariantInTheTopThree() {
        val sun = words.getValue("sun")
        assertTrue(sun.take(3).contains("🌞"), sun.toString())
    }

    @Test fun commonWordsMatchExpressiveEmoji() {
        assertTrue(words.getValue("love").any { "❤" in it }, words.getValue("love").toString())
        assertTrue(words.getValue("fire").first() == "🔥", words.getValue("fire").toString())
        assertTrue(words.getValue("happy").any { it in SMILES }, words.getValue("happy").toString())
        assertTrue(words.getValue("cat").take(3).contains("🐱"), words.getValue("cat").toString())
    }

    private fun load(): Map<String, List<String>> {
        val file = listOf(
            File("src/main/assets/emoji_words.tsv"),
            File("app/src/main/assets/emoji_words.tsv"),
        ).first { it.isFile }
        val map = HashMap<String, List<String>>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            }
        }
        return map
    }

    companion object {
        private val SMILES = setOf("😀", "😃", "😄", "😁", "😊", "🙂", "☺")
    }
}
