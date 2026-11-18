// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlusMenuTest {
    @Test fun plusMenuIsTheShortStandardSetInOrder() {
        assertEquals(
            listOf(
                ToolbarKey.EMOJI,
                ToolbarKey.CLIPBOARD,
                ToolbarKey.TEXT_REPLACEMENT,
                ToolbarKey.SETTINGS,
                ToolbarKey.LANGUAGE,
                ToolbarKey.ONE_HANDED,
                ToolbarKey.UNDO,
                ToolbarKey.REDO,
                ToolbarKey.SELECT_ALL,
                ToolbarKey.COPY,
                ToolbarKey.PASTE,
            ),
            enabledToolbarKeys(defaultToolbarPref),
        )
    }

    @Test fun plusMenuHidesDebugCursorAndNetworkItems() {
        val shown = enabledToolbarKeys(defaultToolbarPref).toSet()
        assertFalse(ToolbarKey.BACKGROUND_GATHERING in shown)
        assertFalse(ToolbarKey.LEFT in shown)
        assertFalse(ToolbarKey.RIGHT in shown)
        assertFalse(ToolbarKey.NUMPAD in shown)
        assertFalse(ToolbarKey.DPAD in shown)
        assertFalse(ToolbarKey.VOICE in shown)
        assertFalse(ToolbarKey.SELECT_WORD in shown)
        assertTrue(isLegacyPlusMenu(legacyPlusMenu()))
        assertFalse(isLegacyPlusMenu(enabledToolbarKeys(defaultToolbarPref)))
    }
}
