// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log of dictation events to find out why a dictation failed. It holds timings, durations,
 * word counts and error codes only: never audio and never any recognised or typed text. It stays on the phone.
 */
object DictationLog {
    private const val FILE = "dictation_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 80
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(context: Context, line: String) {
        try {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val lines = (prefs.getString(KEY, "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
            lines.add(format.format(Date()) + " " + line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) { }
    }

    fun read(context: Context): String =
        try { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: "" } catch (_: Exception) { "" }

    fun clear(context: Context) {
        try { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) { }
    }
}
