// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import kotlin.test.Test
import kotlin.test.assertFalse

class ClipboardDefaultTest {
    @Test fun clipboardHistoryIsOffUntilTheUserEnablesIt() {
        assertFalse(Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    }
}
