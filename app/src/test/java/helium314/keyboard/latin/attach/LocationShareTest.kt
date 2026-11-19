// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.attach

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationShareTest {
    @Test fun linkUsesFiveDecimalsAndNothingElse() {
        assertEquals("https://maps.google.com/?q=37.81990,-122.47830", AttachCommit.locationLink(37.8199, -122.4783))
        assertEquals("https://maps.google.com/?q=1.23000,4.56000", AttachCommit.locationLink(1.23, 4.56))
    }

    @Test fun manifestAllowsTapTimeLocationAndRejectsBackgroundAndInternet() {
        val text = File("src/main/AndroidManifest.xml").readText()
        assertFalse(text.contains("android.permission.INTERNET"))
        assertFalse(text.contains("ACCESS_BACKGROUND_LOCATION"))
        assertTrue(text.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(text.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(text.contains("LocationTrampolineActivity"))
        assertTrue(text.contains("android:exported=\"false\""))
    }
}
