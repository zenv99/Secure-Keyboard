// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.File

/**
 * A zip entry may be restored only if the resulting file stays inside [baseDir].
 * This is the check from HeliBoard's backup path-traversal fix.
 */
fun isSafeZipEntry(baseDir: File, entryName: String): Boolean {
    if (entryName.isEmpty() || entryName.indexOf('\u0000') >= 0) return false
    if (File(entryName).isAbsolute) return false
    val base = baseDir.canonicalFile
    val target = File(base, entryName).canonicalFile
    return target.path == base.path || target.path.startsWith(base.path + File.separator)
}
