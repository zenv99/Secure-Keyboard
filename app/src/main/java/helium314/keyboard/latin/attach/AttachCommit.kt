// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.attach

/**
 * Decides whether an editor can take a photo, camera shot, or document from the keyboard.
 * Editors advertise this with [android.view.inputmethod.EditorInfo.getContentMimeTypes].
 * An empty list means the app does not accept files from a keyboard. This never inserts a newline.
 */
object AttachCommit {
    fun accepts(editorMimeTypes: Array<String>?, contentMime: String): Boolean {
        if (contentMime.isBlank() || editorMimeTypes.isNullOrEmpty()) return false
        val mime = contentMime.lowercase()
        return editorMimeTypes.any { pattern -> matches(pattern.lowercase(), mime) }
    }

    /** Name and phone from a one-shot contact pick. Empty fields are omitted. */
    fun contactLine(name: String?, phone: String?): String? {
        val who = name?.trim().orEmpty()
        val number = phone?.trim().orEmpty()
        return when {
            who.isEmpty() && number.isEmpty() -> null
            who.isEmpty() -> number
            number.isEmpty() -> who
            else -> "$who $number"
        }
    }

    private fun matches(pattern: String, mime: String): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern == "*/*" || pattern == mime) return true
        if (pattern.endsWith("/*")) {
            val prefix = pattern.removeSuffix("*")
            return mime.startsWith(prefix)
        }
        return false
    }
}
