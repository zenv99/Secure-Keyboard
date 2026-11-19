// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.Manifest
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PushToTalkRestartTest {
    @Test fun threePressesInARowEachCount() {
        @Suppress("DEPRECATION")
        val app = RuntimeEnvironment.getApplication() as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = PushToTalkController(app, {})
        repeat(3) {
            assertTrue(controller.onPress())
            controller.onRelease()
        }
        assertEquals(3, controller.pressesForTest)
    }

    @Test fun aPressAfterTheFieldWouldBeClearedStillCounts() {
        @Suppress("DEPRECATION")
        val app = RuntimeEnvironment.getApplication() as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = PushToTalkController(app, {})
        assertTrue(controller.onPress())
        controller.onRelease()
        assertTrue(controller.onPress())
        controller.onRelease()
        assertEquals(2, controller.pressesForTest)
    }
}
