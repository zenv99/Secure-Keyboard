// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the REAL keyboard on an emulator against a plain text field. Every bug we fixed for the user gets a check
 * here, so a release can never publish with it broken again.
 */
class KeyboardSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val sample = "this is a long sentence that we are going to delete by holding backspace for a while"

    @Before fun open() {
        val context = instrumentation.context
        val intent = Intent().apply {
            component = ComponentName(context.packageName, KeyboardTestActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.startActivitySync(intent)
    }

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    private fun field() = KeyboardTestActivity.instance!!.field

    private fun text(): String { var t = ""; instrumentation.runOnMainSync { t = field().text.toString() }; return t }

    private fun setText(value: String) = instrumentation.runOnMainSync {
        field().setText(value)
        field().setSelection(value.length)
    }

    private fun selectionLength(): Int {
        var n = 0
        instrumentation.runOnMainSync { n = field().selectionEnd - field().selectionStart }
        return n
    }

    private fun fail(message: String): Nothing {
        val dump = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString().take(6000)
        throw AssertionError("$message\n--- screen ---\n$dump")
    }

    private fun waitFor(description: String, timeout: Long = 15000): UiObject2 =
        device.wait(Until.findObject(By.desc(description)), timeout) ?: fail("'$description' not found on screen")

    @Test fun micIsVisibleWhenTheKeyboardOpensOnAnEmptyField() {
        waitFor("Voice input")
    }

    @Test fun micBecomesSendWhenThereIsText() {
        waitFor("Voice input")
        setText(sample)
        waitFor("Send")
        setText("")
        waitFor("Voice input")
    }

    @Test fun holdingBackspaceKeepsDeleting() = holdBackspace(drift = 0)

    @Test fun holdingBackspaceWithFingerDriftStillDeletesAndNeverSelects() = holdBackspace(drift = 60)

    private fun holdBackspace(drift: Int) {
        waitFor("Voice input")
        setText(sample)
        val delete = waitFor("Delete")
        val b = delete.visibleBounds
        val x = b.centerX()
        val y = b.centerY()
        val before = text().length
        // one long touch: down, (optional drift), hold ~3 s, up
        device.swipe(x, y, x - drift, y, 600)
        Thread.sleep(300)
        val after = text().length
        assertTrue("holding backspace only deleted ${before - after} characters (was $before, now $after)", before - after >= 15)
        assertTrue("text got highlighted instead of deleted (selection ${selectionLength()})", selectionLength() == 0)
    }
}
