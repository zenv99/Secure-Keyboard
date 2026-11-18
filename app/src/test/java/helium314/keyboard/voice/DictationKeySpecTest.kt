// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks the big dictation button. A UI polish must not move or resize it. */
class DictationKeySpecTest {
    @Test fun bigDictationButtonKeepsItsSizeAndPlacement() {
        assertEquals(96, DictationKeySpec.WIDTH_DP)
        assertEquals(4, DictationKeySpec.MARGIN_START_DP)
        assertEquals(5, DictationKeySpec.MARGIN_TOP_DP)
        assertEquals(4, DictationKeySpec.MARGIN_END_DP)
        assertEquals(5, DictationKeySpec.MARGIN_BOTTOM_DP)
        assertEquals(10, DictationKeySpec.PADDING_START_DP)
        assertEquals(4, DictationKeySpec.PADDING_TOP_DP)
        assertEquals(10, DictationKeySpec.PADDING_END_DP)
        assertEquals(4, DictationKeySpec.PADDING_BOTTOM_DP)
        assertEquals(22, DictationKeySpec.CORNER_RADIUS_DP)
        assertEquals(1f, DictationKeySpec.SCALE)
    }
}
