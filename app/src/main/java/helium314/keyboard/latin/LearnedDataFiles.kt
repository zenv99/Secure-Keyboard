// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

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
        return removed
    }
}
