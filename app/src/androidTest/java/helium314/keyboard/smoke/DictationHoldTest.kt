// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smoke

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import helium314.keyboard.latin.LatinIME
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat field like WhatsApp: multi-line, actionSend, flagNoEnterAction.
 * Pressing the big button must not send. Releasing it inserts text and leaves the keyboard up.
 * A later short tap sends once, and the keyboard is still shown. A Done field must not be sent as Done.
 */
class DictationHoldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @After fun close() {
        device.pressBack()
        KeyboardTestActivity.instance?.finish()
    }

    @Test fun whatsAppHoldDoesNotSendAndALaterTapSendsOnce() {
        open(KeyboardTestActivity.FIELD_WHATSAPP)
        val ime = LatinIME.instanceForTest ?: fail("keyboard service was not created")
        assertEquals(0, actions().size)
        val hides = LatinIME.hideRequestsForTest
        val windows = LatinIME.hideWindowsForTest

        val key = waitForVoiceKey("Voice input")
        pointer(MotionEvent.ACTION_DOWN, key.visibleCenter.x, key.visibleCenter.y)
        Thread.sleep(80)
        assertEquals("press-down sent", 0, actions().size)
        assertTrue("keyboard hid on press", shown(ime))

        pointer(MotionEvent.ACTION_UP, key.visibleCenter.x, key.visibleCenter.y)
        Thread.sleep(200)
        assertEquals("release sent", 0, actions().size)
        assertTrue("keyboard hid when the mic was released", shown(ime))

        instrumentation.runOnMainSync {
            ime.onDictatedText("on my way")
            ime.onDictationSessionFinished(true)
        }
        assertTrue("dictation did not insert", text().contains("on my way"))
        assertEquals("dictation performed an editor action", 0, actions().size)
        assertTrue("keyboard hid after transcription", shown(ime))
        assertTrue("field lost focus", focused())
        waitForVoiceKey("Send")

        val send = waitForVoiceKey("Send")
        pointer(MotionEvent.ACTION_DOWN, send.visibleCenter.x, send.visibleCenter.y)
        Thread.sleep(40)
        assertEquals("press on Send sent before release", 0, actions().size)
        pointer(MotionEvent.ACTION_UP, send.visibleCenter.x, send.visibleCenter.y)
        waitForActions(1)
        assertEquals(EditorInfo.IME_ACTION_SEND, actions().first())
        assertEquals(1, actions().count { it == EditorInfo.IME_ACTION_SEND })
        assertTrue("keyboard hid after send", shown(ime))
        assertEquals(hides, LatinIME.hideRequestsForTest)
        assertEquals(windows, LatinIME.hideWindowsForTest)

        val again = waitForVoiceKey("Send")
        pointer(MotionEvent.ACTION_DOWN, again.visibleCenter.x, again.visibleCenter.y)
        Thread.sleep(DictationHoldMillis.HOLD)
        pointer(MotionEvent.ACTION_UP, again.visibleCenter.x, again.visibleCenter.y)
        Thread.sleep(250)
        assertEquals("a hold on Send sent again", 1, actions().size)
        assertTrue("keyboard hid during a hold", shown(ime))
    }

    @Test fun doneFieldStillSendsSendAndStaysOpen() {
        open(KeyboardTestActivity.FIELD_DONE)
        val ime = LatinIME.instanceForTest ?: fail("keyboard service was not created")
        instrumentation.runOnMainSync {
            ime.onDictatedText("hello")
            ime.onDictationSessionFinished(true)
        }
        assertEquals(0, actions().size)
        assertTrue(shown(ime))
        val send = waitForVoiceKey("Send")
        pointer(MotionEvent.ACTION_DOWN, send.visibleCenter.x, send.visibleCenter.y)
        Thread.sleep(40)
        pointer(MotionEvent.ACTION_UP, send.visibleCenter.x, send.visibleCenter.y)
        waitForActions(1)
        assertEquals(EditorInfo.IME_ACTION_SEND, actions().first())
        assertEquals(1, actions().count { it == EditorInfo.IME_ACTION_SEND })
        assertTrue(actions().none { it == EditorInfo.IME_ACTION_DONE })
        assertTrue("Done field hid the keyboard", shown(ime))
    }

    private fun open(field: String) {
        LatinIME.hideRequestsForTest = 0
        LatinIME.hideWindowsForTest = 0
        val context = instrumentation.targetContext
        val intent = Intent().apply {
            component = ComponentName(context.packageName, KeyboardTestActivity::class.java.name)
            putExtra(KeyboardTestActivity.EXTRA_FIELD, field)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.startActivitySync(intent)
        val pkg = context.packageName
        val end = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < end) {
            device.findObject(By.desc("smoke-field"))?.click()
            if (device.wait(Until.findObject(By.res(pkg, "sk_voice_key")), 1500) != null) return
        }
        fail("keyboard did not open")
    }

    private var downAt = 0L

    private fun pointer(action: Int, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val event = MotionEvent.obtain(downAt, now, action, x.toFloat(), y.toFloat(), 0)
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun waitForVoiceKey(description: String) = device.wait(
        Until.findObject(By.res(instrumentation.targetContext.packageName, "sk_voice_key").desc(description)),
        20000,
    ) ?: fail("voice key '$description' not shown")

    private fun actions(): List<Int> {
        val copy = mutableListOf<Int>()
        instrumentation.runOnMainSync { copy.addAll(KeyboardTestActivity.instance!!.editorActions) }
        return copy
    }

    private fun waitForActions(count: Int) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && actions().size < count) Thread.sleep(50)
    }

    private fun text(): String {
        var value = ""
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.text.toString() }
        return value
    }

    private fun focused(): Boolean {
        var value = false
        instrumentation.runOnMainSync { value = KeyboardTestActivity.instance!!.field.hasFocus() }
        return value
    }

    private fun shown(ime: LatinIME): Boolean {
        var value = false
        instrumentation.runOnMainSync { value = ime.isInputViewShown && KeyboardTestActivity.instance!!.field.hasFocus() }
        return value
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private object DictationHoldMillis {
        const val HOLD = 650L
    }
}
