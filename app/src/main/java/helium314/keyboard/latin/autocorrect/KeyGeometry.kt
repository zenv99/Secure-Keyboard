// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import kotlin.math.hypot

/**
 * QWERTY key centres in key-width units, with the usual row stagger.
 * A real keyboard can supply its own distances; letters the layout does not have
 * fall back to this map so the corrector still runs.
 */
object KeyGeometry {
    const val ADJACENT = 1.55f
    const val FAR = 8f

    private data class Point(val x: Float, val y: Float)

    private val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val rowOffset = floatArrayOf(0f, 0.5f, 1.5f)
    private val pos: Map<Char, Point> = buildMap {
        for (r in rows.indices) {
            val row = rows[r]
            for (c in row.indices) {
                put(row[c], Point(c + rowOffset[r], r.toFloat()))
            }
        }
    }

    fun qwertyDistance(a: Char, b: Char): Float {
        if (a == b) return 0f
        val pa = pos[baseLetter(a)] ?: return FAR
        val pb = pos[baseLetter(b)] ?: return FAR
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /** Key centre in key-width units, or null when the letter is not on this map. */
    fun centre(ch: Char): FloatArray? {
        val p = pos[baseLetter(ch)] ?: return null
        return floatArrayOf(p.x, p.y)
    }

    /** Fold accents so "é" sits on the E key. */
    fun baseLetter(ch: Char): Char {
        val c = ch.lowercaseChar()
        return when (c) {
            'à', 'á', 'â', 'ä', 'ã', 'å', 'ā' -> 'a'
            'ç', 'ć', 'č' -> 'c'
            'è', 'é', 'ê', 'ë', 'ē' -> 'e'
            'ì', 'í', 'î', 'ï', 'ī' -> 'i'
            'ñ', 'ń' -> 'n'
            'ò', 'ó', 'ô', 'ö', 'õ', 'ø', 'ō' -> 'o'
            'ù', 'ú', 'û', 'ü', 'ū' -> 'u'
            'ý', 'ÿ' -> 'y'
            'ß' -> 's'
            else -> c
        }
    }
}
