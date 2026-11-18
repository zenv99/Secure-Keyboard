// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import android.content.Context
import java.io.File
import kotlin.jvm.JvmStatic

/**
 * On-device store for text replacements. The file lives in app-private storage.
 * Android backup and device transfer are disabled for the whole app, so this file stays on the phone.
 * The phrases are never written to the log.
 */
object TextReplacements {
    private const val FILE_NAME = "text_replacements.txt"

    @Volatile
    private var entries: List<TextReplacement> = emptyList()
    @Volatile
    private var loaded = false

    @JvmStatic
    fun expand(typed: String?): String? {
        if (typed.isNullOrEmpty()) return null
        return TextReplacementRules.lookup(entries, typed)
    }

    fun all(): List<TextReplacement> = entries

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val file = file(context)
            entries = if (!file.exists()) {
                emptyList()
            } else {
                val decoded = runCatching { TextReplacementRules.decode(file.readText()) }.getOrNull()
                if (decoded == null) {
                    // Keep the unreadable file instead of destroying it, and start from an empty list.
                    file.renameTo(File(file.parentFile, "$FILE_NAME.bak"))
                    emptyList()
                } else {
                    decoded
                }
            }
            loaded = true
        }
    }

    fun replaceAll(context: Context, updated: List<TextReplacement>) {
        val cleaned = updated.map { TextReplacement(it.shortcut.trim(), it.phrase.trim()) }
        for (entry in cleaned) {
            if (TextReplacementRules.validate(entry.shortcut, entry.phrase) != null) return
        }
        file(context).writeText(TextReplacementRules.encode(cleaned))
        entries = cleaned
        loaded = true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
