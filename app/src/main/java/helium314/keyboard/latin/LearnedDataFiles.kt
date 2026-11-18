// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.autocorrect.AcceptedTypedWords
import java.io.File

/** User-history dictionary files live only in app-private storage and are named with this prefix. */
object LearnedDataFiles {
    const val USER_HISTORY_PREFIX = "UserHistoryDictionary"

    fun isUserHistoryName(name: String): Boolean = name.startsWith(USER_HISTORY_PREFIX)

    /** Deletes history files in [dir]. Returns how many were removed. Other files are left alone. */
    fun deleteUserHistoryFiles(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!isUserHistoryName(file.name)) continue
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (ok) removed++
        }
        val accepted = File(dir, AcceptedTypedWords.FILE_NAME)
        if (accepted.isFile && accepted.delete()) {
            removed++
            AcceptedTypedWords.forgetIfLoadedFrom(dir)
        }
        return removed
    }
}
