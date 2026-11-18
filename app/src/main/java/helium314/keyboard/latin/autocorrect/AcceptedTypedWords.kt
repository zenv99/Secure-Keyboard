// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import java.io.File

/**
 * Words the user took back with backspace after an autocorrection.
 * Stored only in app-private storage, never logged. Password and other
 * no-learning fields must not call [remember], and must not consult this set.
 */
object AcceptedTypedWords {
    const val FILE_NAME = "accepted_typed_words.txt"
    private const val MAX_WORDS = 2000

    private val words = LinkedHashSet<String>()
    private var loadedDir: File? = null

    @JvmStatic
    @Synchronized
    fun ensureLoaded(dir: File) {
        if (loadedDir == dir) return
        words.clear()
        val file = File(dir, FILE_NAME)
        if (file.isFile) {
            file.readLines().forEach { line ->
                val word = normalize(line) ?: return@forEach
                words.add(word)
            }
            trim()
        }
        loadedDir = dir
    }

    @JvmStatic
    @Synchronized
    fun remembers(word: String): Boolean {
        val key = normalize(word) ?: return false
        return words.contains(key)
    }

    /** Remember [typed] so the next attempt is left as the user spelled it. */
    @JvmStatic
    @Synchronized
    fun remember(dir: File, typed: String) {
        ensureLoaded(dir)
        val key = normalize(typed) ?: return
        words.remove(key)
        words.add(key)
        trim()
        write(dir)
    }

    @Synchronized
    fun forgetIfLoadedFrom(dir: File) {
        if (loadedDir == dir) {
            words.clear()
            loadedDir = dir
        }
    }

    private fun trim() {
        val extra = words.size - MAX_WORDS
        if (extra <= 0) return
        val it = words.iterator()
        var left = extra
        while (left > 0 && it.hasNext()) {
            it.next()
            it.remove()
            left--
        }
    }

    private fun write(dir: File) {
        val file = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(words.joinToString("\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun normalize(raw: String): String? {
        val word = raw.trim().lowercase()
        if (word.length !in 2..40) return null
        if (word.any { it.isWhitespace() || it.isDigit() || it == '@' || it == '/' || it == ':' }) return null
        if (word.none { it.isLetter() }) return null
        return word
    }
}
