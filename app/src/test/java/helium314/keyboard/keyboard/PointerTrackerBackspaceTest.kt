// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlin.test.Test
import kotlin.test.assertFalse

/** Regression guard: holding backspace must repeat-delete, never turn into a text-selecting slide. */
class PointerTrackerBackspaceTest {
    @Test fun deleteSwipeNeverSelectsText() {
        assertFalse(PointerTracker.DELETE_SWIPE_SELECTS_TEXT, "backspace slide-to-select cancels hold-repeat")
    }
}
