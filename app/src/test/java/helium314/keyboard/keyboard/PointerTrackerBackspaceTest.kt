// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression guard: holding backspace must repeat-delete, never turn into a text-selecting slide. */
class PointerTrackerBackspaceTest {
    @Test fun deleteSwipeNeverSelectsText() {
        assertFalse(PointerTracker.DELETE_SWIPE_SELECTS_TEXT, "backspace slide-to-select cancels hold-repeat")
    }

    @Test fun aQuickSpaceTapIsNotTheTrackpad() {
        // Backspace, then space, is two short taps. The trackpad arms only after a hold.
        assertTrue(PointerTracker.SPACE_HOLD_FOR_TRACKPAD_MILLIS >= 300)
    }
}
