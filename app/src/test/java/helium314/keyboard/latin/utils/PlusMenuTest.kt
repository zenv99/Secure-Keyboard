// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.checkVersionUpgrade
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
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
                ToolbarKey.CONTACT,
                ToolbarKey.DOCUMENT,
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

    @Test fun aFreshInstallGetsTheStandardMenu() {
        val prefs = prefs("fresh")
        assertFalse(prefs.contains(Settings.PREF_TOOLBAR_KEYS))
        assertEquals(standardPlusMenu, getEnabledToolbarKeys(prefs))
    }

    @Test fun anOldBuiltInDefaultIsMigratedOnce() {
        for (old in listOf(legacyPlusMenu(), previousPlusMenu(), plusMenuBeforeLocation(), heliBoardDefaultPlusMenu())) {
            val prefs = appPrefs()
            saveMenu(prefs, old)
            prefs.edit().putInt(Settings.PREF_VERSION_CODE, 4434).commit()
            checkVersionUpgrade(context())
            assertEquals(standardPlusMenu, getEnabledToolbarKeys(prefs), old.toString())
            val afterFirst = prefs.getString(Settings.PREF_TOOLBAR_KEYS, "")
            checkVersionUpgrade(context())
            upgradeToolbarPrefs(prefs)
            assertEquals(afterFirst, prefs.getString(Settings.PREF_TOOLBAR_KEYS, ""))
            assertEquals(standardPlusMenu, getEnabledToolbarKeys(prefs))
        }
    }

    @Test fun aCustomizedMenuSurvivesAVersionChangeAndASecondUpgrade() {
        val prefs = appPrefs()
        val custom = listOf(
            ToolbarKey.PHOTO, ToolbarKey.CAMERA, ToolbarKey.CONTACT, ToolbarKey.DOCUMENT,
            ToolbarKey.EMOJI, ToolbarKey.CLIPBOARD, ToolbarKey.TEXT_REPLACEMENT, ToolbarKey.SETTINGS,
        )
        saveMenu(prefs, custom)
        prefs.edit().putInt(Settings.PREF_VERSION_CODE, 4434).commit()
        checkVersionUpgrade(context())
        assertEquals(custom, getEnabledToolbarKeys(prefs))
        val saved = prefs.getString(Settings.PREF_TOOLBAR_KEYS, "")
        prefs.edit().putInt(Settings.PREF_VERSION_CODE, 4430).commit()
        checkVersionUpgrade(context())
        upgradeToolbarPrefs(prefs)
        assertEquals(custom, getEnabledToolbarKeys(prefs))
        assertEquals(saved, prefs.getString(Settings.PREF_TOOLBAR_KEYS, ""))
    }

    @Test fun aReorderedMenuSurvivesAVersionChangeAndASecondUpgrade() {
        val prefs = appPrefs()
        val reordered = standardPlusMenu.toMutableList().apply {
            val photo = removeAt(0)
            add(2, photo)
        }
        saveMenu(prefs, reordered)
        prefs.edit().putInt(Settings.PREF_VERSION_CODE, 4400).commit()
        checkVersionUpgrade(context())
        assertEquals(reordered, getEnabledToolbarKeys(prefs))
        upgradeToolbarPrefs(prefs)
        checkVersionUpgrade(context())
        assertEquals(reordered, getEnabledToolbarKeys(prefs))
        assertFalse(reordered == standardPlusMenu)
    }

    @Test fun addingAMissingKeyDoesNotResetTheSavedOrder() {
        val prefs = prefs("missing")
        saveMenu(prefs, listOf(ToolbarKey.SETTINGS, ToolbarKey.EMOJI))
        upgradeToolbarPrefs(prefs)
        assertEquals(listOf(ToolbarKey.SETTINGS, ToolbarKey.EMOJI), getEnabledToolbarKeys(prefs))
        assertTrue(prefs.getString(Settings.PREF_TOOLBAR_KEYS, "")!!.contains("PHOTO:false"))
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun appPrefs(): SharedPreferences {
        val prefs = context().prefs()
        prefs.edit().clear().commit()
        return prefs
    }

    private fun prefs(name: String): SharedPreferences {
        val prefs = context().getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return prefs
    }

    private fun saveMenu(prefs: SharedPreferences, enabled: List<ToolbarKey>) {
        val on = enabled.toSet()
        val others = ToolbarKey.entries.filterNot { it in on || it == ToolbarKey.CLOSE_HISTORY }
        val value = enabled.joinToString(Separators.ENTRY) { it.name + Separators.KV + true } +
            Separators.ENTRY +
            others.joinToString(Separators.ENTRY) { it.name + Separators.KV + false }
        prefs.edit().putString(Settings.PREF_TOOLBAR_KEYS, value).commit()
    }
}
