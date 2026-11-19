// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.settings.Defaults
import kotlin.test.Test
import kotlin.test.assertTrue

/** On-device learning ships on. Nothing here is sent off the phone, and settings can wipe it. */
class LearningDefaultsTest {
    @Test fun learningIsOnByDefault() {
        assertTrue(Defaults.PREF_KEY_USE_PERSONALIZED_DICTS)
        assertTrue(Defaults.PREF_BIGRAM_PREDICTIONS)
        assertTrue(Defaults.PREF_AUTO_CORRECTION)
        assertTrue(Defaults.PREF_AUTO_CAP)
        assertTrue(Defaults.PREF_BACKSPACE_REVERTS_AUTOCORRECT)
        assertTrue(!Defaults.PREF_ALWAYS_INCOGNITO_MODE)
    }
}
