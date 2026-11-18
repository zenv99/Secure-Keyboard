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
        val context = instrumentation.targetContext
        val intent = Intent().apply {
            component = ComponentName(context.packageName, KeyboardTestActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.startActivitySync(intent)
        // tap into the field like a user does: this is what makes Android start and show the keyboard
        device.wait(Until.findObject(By.desc("smoke-field")), 15000)?.click()
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
        val ime = device.executeShellCommand("dumpsys input_method").lines()
            .filter { Regex("mInputShown|mShowRequested|mCurId|mCurMethodId|isInputViewShown|mImeWindowVis").containsMatchIn(it) }
            .joinToString("\n").take(1500)
        val crashes = device.executeShellCommand("logcat -d -b crash").takeLast(3000)
        val errors = device.executeShellCommand("logcat -d -s AndroidRuntime:E ime:E InputMethodManager:W").takeLast(2500)
        throw AssertionError("$message\n--- input method ---\n$ime\n--- crash log ---\n$crashes\n--- errors ---\n$errors\n--- all windows ---\n${dumpImeWindows()}\n--- screen ---\n${dump.take(800)}")
    }

    /** The big mic / send key, found by its fixed id; its label tells which mode it is in. */
    private fun waitForVoiceKey(expectedDesc: String? = null): UiObject2 {
        val pkg = instrumentation.targetContext.packageName
        val end = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < end) {
            val node = device.findObject(By.res(pkg, "sk_voice_key"))
            if (node != null && (expectedDesc == null || node.contentDescription == expectedDesc)) return node
            Thread.sleep(300)
        }
        fail("voice key (id sk_voice_key${expectedDesc?.let { ", desc '$it'" } ?: ""}) not found")
    }

    private fun dumpImeWindows(): String {
        val sb = StringBuilder()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 12) return
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            run {
                sb.append(" ".repeat(depth)).append(n.className).append(" id=").append(n.viewIdResourceName)
                    .append(" desc=").append(n.contentDescription).append(" ").append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        for (w in instrumentation.uiAutomation.windows) {
            sb.append("WINDOW type=").append(w.type).append(" title=").append(w.title).append('\n')
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) walk(w.root, 0)
        }
        return sb.toString().take(6000)
    }

    private fun waitFor(description: String, timeout: Long = 60000): UiObject2 =
        device.wait(Until.findObject(By.desc(description)), timeout) ?: fail("'$description' not found on screen")

    @Test fun micIsVisibleWhenTheKeyboardOpensOnAnEmptyField() {
        waitForVoiceKey("Voice input")
    }

    @Test fun micBecomesSendWhenThereIsText() {
        waitForVoiceKey("Voice input")
        setText(sample)
        waitForVoiceKey("Send")
        setText("")
        waitForVoiceKey("Voice input")
    }

    @Test fun holdingBackspaceKeepsDeleting() = holdBackspace(drift = 0)

    @Test fun holdingBackspaceWithFingerDriftStillDeletesAndNeverSelects() = holdBackspace(drift = 60)

    private fun holdBackspace(drift: Int) {
        waitForVoiceKey("Voice input")
        setText(sample)
        val delete = waitFor("Delete")
        val b = delete.visibleBounds
        val x = b.centerX()
        val y = b.centerY()
        val before = text().length
        // one long touch: down, (optional drift), hold ~3 s, up
        // `input swipe` with a duration is a real press-and-hold (identical start/end = a stationary finger)
        device.executeShellCommand("input swipe $x $y ${x - drift} $y 3000")
        Thread.sleep(500)
        val after = text().length
        assertTrue("holding backspace only deleted ${before - after} characters (was $before, now $after)", before - after >= 15)
        assertTrue("text got highlighted instead of deleted (selection ${selectionLength()})", selectionLength() == 0)
    }
}
