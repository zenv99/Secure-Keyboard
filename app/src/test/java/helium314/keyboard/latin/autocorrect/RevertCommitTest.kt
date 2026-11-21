// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.autocorrect

import helium314.keyboard.latin.CapsMode
import helium314.keyboard.latin.LastComposedWord
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOSP LastComposedWord: backspace reverts only a commit that changed the typed word. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RevertCommitTest {
    @Test fun backspaceAfterAnAutocorrectCanRevertTheCommit() {
        val corrected = LastComposedWord(
            ArrayList(), null, "gpod", "good", " ", null, CapsMode.OFF,
        )
        assertTrue(corrected.canRevertCommit())
        val typedOnly = LastComposedWord(
            ArrayList(), null, "webull", "webull", " ", null, CapsMode.OFF,
        )
        assertFalse(typedOnly.canRevertCommit())
        corrected.deactivate()
        assertFalse(corrected.canRevertCommit())
    }
}
