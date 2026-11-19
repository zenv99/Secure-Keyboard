// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlusMenuTest {
    @Test fun plusMenuIsTheShortStandardSetInOrder() {
        assertEquals(
            listOf(
                ToolbarKey.PHOTO,
                ToolbarKey.CAMERA,
                ToolbarKey.LOCATION,
                ToolbarKey.DOCUMENT,
                ToolbarKey.CONTACT,
                ToolbarKey.EMOJI,
                ToolbarKey.CLIPBOARD,
                ToolbarKey.TEXT_REPLACEMENT,
                ToolbarKey.SETTINGS,
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

    @Test fun anOldSavedMenuIsReplacedForEveryone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("plus-upgrade", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(
            Settings.PREF_TOOLBAR_KEYS,
            "SETTINGS:true|UNDO:true|REDO:true|LEFT:true|RIGHT:true|NUMPAD:true",
        ).commit()
        upgradeToolbarPrefs(prefs)
        assertEquals(standardPlusMenu, getEnabledToolbarKeys(prefs))
        assertFalse(ToolbarKey.LEFT in getEnabledToolbarKeys(prefs))
        assertFalse(ToolbarKey.UNDO in getEnabledToolbarKeys(prefs))
    }
}
